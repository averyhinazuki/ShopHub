package com.example.shophub.kafka.consumer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.dto.OrderResponse;
import com.example.shophub.dto.order.CheckoutStatusResponse;
import com.example.shophub.exception.SoldOutException;
import com.example.shophub.kafka.event.CheckoutRequestedEvent;
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
 * Processes checkout-requested events published by OrderService.initiateCheckout().
 * Runs the stock-deduction + order-creation logic asynchronously and writes the
 * result (SUCCESS or FAILED) to Redis so clients can poll for it.
 *
 * Idempotency: duplicate deliveries (same checkoutId key) are skipped via Redis dedup.
 * SoldOutException: terminal — writes FAILED to Redis, does NOT rethrow (no retry desired).
 * Other exceptions: rethrown so @RetryableTopic retries up to 3 times with exponential backoff.
 * After retry exhaustion: @DltHandler fires and writes FAILED to Redis.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutRequestedConsumer {

    private final OrderService             orderService;
    private final ConsumerIdempotencyGuard idempotencyGuard;
    private final StringRedisTemplate      redisTemplate;
    private final ObjectMapper             objectMapper;

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
            // Swallow dedup-write failure: status was already written to Redis and the cart
            // is now cleared, so a retry would only create "cart empty" failures → DLT.
            // Better to commit the offset here than trigger a pointless retry chain.
            safeMarkProcessed(key);
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

        } catch (Exception e) {
            // Transient failure (DB unavailable, lock timeout) — rethrow for @RetryableTopic
            log.error("[Kafka][checkout-requested] Error for checkoutId={}: {}",
                    event.getCheckoutId(), e.getMessage());
            throw e;
        }
    }

    @DltHandler
    public void handleDlt(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        log.error("[Kafka][DLT] Retries exhausted on topic={}: {}", topic, message);
        try {
            CheckoutRequestedEvent event = objectMapper.readValue(message, CheckoutRequestedEvent.class);
            // Guard: a rare Redis blip between writeStatus(SUCCESS) and markProcessed could
            // cause this DLT delivery. Do not overwrite a SUCCESS with FAILED.
            String existing = redisTemplate.opsForValue().get("checkout:" + event.getCheckoutId());
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

    void writeStatus(String checkoutId, CheckoutStatusResponse status) {
        try {
            redisTemplate.opsForValue().set(
                    "checkout:" + checkoutId,
                    objectMapper.writeValueAsString(status),
                    Duration.ofMinutes(checkoutStatusTtlMinutes));
        } catch (JsonProcessingException e) {
            log.error("[Kafka][checkout-requested] Failed to write status for checkoutId={}", checkoutId);
        }
    }
}
