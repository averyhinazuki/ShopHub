package com.example.shophub.kafka.consumer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.dto.OrderResponse;
import com.example.shophub.dto.order.CheckoutStatusResponse;
import com.example.shophub.exception.SoldOutException;
import com.example.shophub.kafka.event.CheckoutRequestedEvent;
import com.example.shophub.metrics.DomainMetrics;
import com.example.shophub.service.OrderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Consumes checkout-requested events, runs stock deduction and order creation,
 * and writes the SUCCESS/FAILED result to Redis for the client to poll.
 *
 * Duplicate deliveries are skipped via the Redis dedup guard. SoldOutException is
 * terminal — FAILED written, not rethrown. Other exceptions propagate to
 * @RetryableTopic (exponential backoff); on exhaustion @DltHandler writes FAILED.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutRequestedConsumer {

    private final OrderService             orderService;
    private final ConsumerIdempotencyGuard idempotencyGuard;
    private final StringRedisTemplate      redisTemplate;
    private final ObjectMapper             objectMapper;
    private final DomainMetrics            metrics;

    @Value("${app.checkout.status-ttl-minutes:30}")
    private int checkoutStatusTtlMinutes;

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 1000, multiplier = 2.0), autoCreateTopics = "true")
    @KafkaListener(topics = KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, groupId = "flash-sale-group")
    public void handleCheckoutRequested(
            String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {

        if (idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, key)) {
            log.warn("[Kafka][checkout-requested] Duplicate checkoutId={} — skipping", key);
            return;
        }

        CheckoutRequestedEvent event;
        try {
            event = objectMapper.readValue(message, CheckoutRequestedEvent.class);
        } catch (JsonProcessingException e) {
            log.error("[Kafka][checkout-requested] Malformed message — skipping: {}", e.getMessage());
            return;
        }

        log.info("[Kafka][checkout-requested] Processing checkoutId={} userId={}",
                event.getCheckoutId(), event.getUserId());

        try {
            OrderResponse order = orderService.processCheckout(event.getUserId());
            writeStatus(event.getCheckoutId(), CheckoutStatusResponse.builder()
                    .checkoutId(event.getCheckoutId())
                    .status("SUCCESS")
                    .orderId(order.getId())
                    .build());
            // Status is written and the cart is cleared, so a retry would only fail
            // on an empty cart — mark processed and let the offset commit.
            safeMarkProcessed(key);
            metrics.checkoutSucceeded();
            log.info("[Kafka][checkout-requested] checkoutId={} → orderId={}",
                    event.getCheckoutId(), order.getId());

        } catch (SoldOutException e) {
            log.warn("[Kafka][checkout-requested] Sold out for checkoutId={}: {}",
                    event.getCheckoutId(), e.getMessage());
            writeStatus(event.getCheckoutId(), CheckoutStatusResponse.builder()
                    .checkoutId(event.getCheckoutId())
                    .status("FAILED")
                    .failureReason("Sold out: " + e.getMessage())
                    .build());
            safeMarkProcessed(key);
            metrics.checkoutSoldOut();

        } catch (Exception e) {
            // Transient failure (DB down, lock timeout) — rethrow to retry.
            log.error("[Kafka][checkout-requested] Error for checkoutId={}: {}",
                    event.getCheckoutId(), e.getMessage());
            throw e;
        }
    }

    @DltHandler
    public void handleDlt(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        metrics.checkoutReachedDlt();
        log.error("[Kafka][DLT] Retries exhausted on topic={}: {}", topic, message);
        try {
            CheckoutRequestedEvent event = objectMapper.readValue(message, CheckoutRequestedEvent.class);
            // Don't overwrite a SUCCESS with FAILED — a Redis blip between
            // writeStatus and markProcessed can still route a succeeded item here.
            String existing;
            try {
                existing = redisTemplate.opsForValue().get("checkout:" + event.getCheckoutId());
            } catch (Exception e) {
                // Can't read the current status, so we can't rule out a SUCCESS sitting
                // there unread. Writing FAILED blind is the very mistake this guard exists
                // to prevent, so say nothing rather than say something false.
                log.error("[Kafka][DLT] Cannot read status for checkoutId={} — leaving it alone "
                                + "rather than risk overwriting a SUCCESS: {}",
                        event.getCheckoutId(), e.getMessage());
                return;
            }
            if (existing != null) {
                try {
                    if ("SUCCESS".equals(objectMapper.readValue(existing, CheckoutStatusResponse.class).getStatus())) {
                        log.warn("[Kafka][DLT] Status already SUCCESS for checkoutId={} — skipping FAILED write",
                                event.getCheckoutId());
                        return;
                    }
                } catch (JsonProcessingException ignored) { /* corrupt entry — fall through to write FAILED */ }
            }
            writeStatus(event.getCheckoutId(), CheckoutStatusResponse.builder()
                    .checkoutId(event.getCheckoutId())
                    .status("FAILED")
                    .failureReason("Checkout exhausted all retries — please try again")
                    .build());
        } catch (JsonProcessingException e) {
            log.error("[Kafka][DLT] Cannot deserialize DLT message — checkout status not updated: {}", e.getMessage());
        }
    }

    private void safeMarkProcessed(String key) {
        try {
            idempotencyGuard.markProcessed(KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, key);
        } catch (Exception e) {
            log.warn("[Kafka][checkout-requested] Dedup mark failed for key={} — status already written, offset will commit: {}",
                    key, e.getMessage());
        }
    }

    /**
     * Records the outcome for the polling client. Best-effort on purpose: this runs
     * *after* processCheckout has committed, so the order already exists and failing
     * the message here can only do harm.
     *
     * It used to catch JsonProcessingException only, which made a Redis blip
     * catastrophic: RedisConnectionFailureException escaped, @RetryableTopic
     * redelivered, loadCartSnapshot found the cart already cleared and threw, retries
     * exhausted, and @DltHandler — seeing a status still stuck at PENDING, so its
     * SUCCESS-guard never tripped — wrote FAILED. The customer was told their
     * checkout failed while a real, payable order sat in the database, and per F8
     * they would then retry and create a second one.
     *
     * The status is a courtesy record, not the source of truth. If it cannot be
     * written the client gets a 404 from getCheckoutStatus (F9) and is told to check
     * their orders, which is at least true.
     */
    void writeStatus(String checkoutId, CheckoutStatusResponse status) {
        try {
            redisTemplate.opsForValue().set(
                    "checkout:" + checkoutId,
                    objectMapper.writeValueAsString(status),
                    Duration.ofMinutes(checkoutStatusTtlMinutes));
        } catch (Exception e) {
            metrics.checkoutStatusWriteFailed();
            log.error("[Kafka][checkout-requested] Failed to write status={} for checkoutId={} — "
                            + "the order itself is unaffected: {}",
                    status.getStatus(), checkoutId, e.getMessage());
        }
    }
}
