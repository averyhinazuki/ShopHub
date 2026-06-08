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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Processes checkout-requested events published by OrderService.initiateCheckout().
 * Runs the stock-deduction + order-creation logic asynchronously and writes the
 * result (SUCCESS or FAILED) to Redis so clients can poll for it.
 *
 * SoldOutException: terminal — writes FAILED to Redis, does NOT rethrow.
 * Other exceptions: rethrown so they propagate (retry/DLT added in a later milestone).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutRequestedConsumer {

    private final OrderService        orderService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper        objectMapper;

    @Value("${app.checkout.status-ttl-minutes:30}")
    private int checkoutStatusTtlMinutes;

    @KafkaListener(topics = KafkaTopicConfig.CHECKOUT_REQUESTED_TOPIC, groupId = "flash-sale-group")
    public void handleCheckoutRequested(String message) {
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

        } catch (Exception e) {
            log.error("[Kafka][checkout-requested] Error for checkoutId={}: {}",
                    event.getCheckoutId(), e.getMessage());
            throw e;
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
