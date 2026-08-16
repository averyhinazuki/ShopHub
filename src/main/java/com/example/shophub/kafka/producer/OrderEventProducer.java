package com.example.shophub.kafka.producer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.dto.order.CheckoutStatusResponse;
import com.example.shophub.kafka.event.CheckoutRequestedEvent;
import com.example.shophub.kafka.event.OrderCreatedEvent;
import com.example.shophub.kafka.event.PaymentCompletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${app.checkout.status-ttl-minutes:30}")
    private int checkoutStatusTtlMinutes;

    public void sendOrderCreatedEvent(OrderCreatedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = String.valueOf(event.getOrderId());

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(KafkaTopicConfig.ORDER_CREATED_TOPIC, key, payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[Kafka] order-created sent: orderId={} partition={} offset={}",
                            event.getOrderId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[Kafka] Failed to send order-created event for orderId={}: {}",
                            event.getOrderId(), ex.getMessage());
                }
            });
        } catch (JsonProcessingException e) {
            log.error("[Kafka] Serialization error for OrderCreatedEvent: {}", e.getMessage());
        }
    }

    public void sendPaymentCompletedEvent(PaymentCompletedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            String key = String.valueOf(event.getOrderId());

            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, key, payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[Kafka] payment-completed sent: orderId={} partition={} offset={}",
                            event.getOrderId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[Kafka] Failed to send payment-completed event for orderId={}: {}",
                            event.getOrderId(), ex.getMessage());
                }
            });
        } catch (JsonProcessingException e) {
            log.error("[Kafka] Serialization error for PaymentCompletedEvent: {}", e.getMessage());
        }
    }

    public void sendCheckoutRequestedEvent(CheckoutRequestedEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send(KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, event.getCheckoutId(), payload);

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    log.info("[Kafka] checkout-requested sent: checkoutId={} partition={} offset={}",
                            event.getCheckoutId(),
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset());
                } else {
                    log.error("[Kafka] Failed to send checkout-requested for checkoutId={}: {}",
                            event.getCheckoutId(), ex.getMessage());
                    markCheckoutFailed(event.getCheckoutId());
                }
            });
        } catch (JsonProcessingException e) {
            log.error("[Kafka] Serialization error for CheckoutRequestedEvent: {}", e.getMessage());
            markCheckoutFailed(event.getCheckoutId());
        }
    }

    /**
     * Records a checkout as FAILED when its event never reached the broker.
     *
     * The send is fire-and-forget — whenComplete only logs — so a broker outage
     * used to lose the message in silence. initiateCheckout has by then already
     * written PENDING and returned 202, so the status record described work that
     * was never queued, and the client would poll that key until its TTL ran out.
     * Writing FAILED here turns a lost message into an observable outcome using
     * machinery that already exists.
     *
     * Best-effort by definition: this runs on a Kafka callback thread, and if
     * Redis is down too there is nothing left to record to. It must never throw.
     */
    private void markCheckoutFailed(String checkoutId) {
        try {
            CheckoutStatusResponse failed = CheckoutStatusResponse.builder()
                    .checkoutId(checkoutId)
                    .status("FAILED")
                    .failureReason("Checkout could not be queued — please try again")
                    .build();
            redisTemplate.opsForValue().set(
                    "checkout:" + checkoutId,
                    objectMapper.writeValueAsString(failed),
                    Duration.ofMinutes(checkoutStatusTtlMinutes));
            log.warn("[Kafka] checkoutId={} marked FAILED — event never reached the broker", checkoutId);
        } catch (Exception e) {
            log.error("[Kafka] Could not mark checkoutId={} FAILED after a send failure: {}",
                    checkoutId, e.getMessage());
        }
    }
}
