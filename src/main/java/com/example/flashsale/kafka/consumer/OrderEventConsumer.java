package com.example.flashsale.kafka.consumer;

import com.example.flashsale.config.KafkaTopicConfig;
import com.example.flashsale.kafka.event.OrderCreatedEvent;
import com.example.flashsale.kafka.event.PaymentCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes order-created and payment-completed events from Kafka.
 *
 * Step 9  — receives and logs events; confirms the full publish-after-commit pipeline works.
 * Step 10 — adds MongoDB writes: order_activity_log on each event.
 *
 * Note: order status is NOT updated here. The /pay endpoint already performs the
 * PENDING → PAID transition via a synchronous conditional UPDATE before the
 * payment-completed event is published. The consumer's job is downstream processing
 * (logging, notifications) — not state mutation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    // Step 10: inject OrderActivityLogRepository here

    @KafkaListener(topics = KafkaTopicConfig.ORDER_CREATED_TOPIC, groupId = "flash-sale-group")
    public void handleOrderCreated(String message) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            log.info("[Kafka][order-created] orderId={} userId={} createdAt={}",
                    event.getOrderId(), event.getUserId(), event.getCreatedAt());
            // Step 10: write { orderId, userId, event: "ORDER_CREATED", timestamp } to MongoDB
        } catch (Exception e) {
            log.error("[Kafka][order-created] Failed to process message: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, groupId = "flash-sale-group")
    public void handlePaymentCompleted(String message) {
        try {
            PaymentCompletedEvent event = objectMapper.readValue(message, PaymentCompletedEvent.class);
            log.info("[Kafka][payment-completed] orderId={} userId={} paidAt={}",
                    event.getOrderId(), event.getUserId(), event.getPaidAt());
            // Step 10: write { orderId, userId, event: "PAYMENT_COMPLETED", timestamp } to MongoDB
        } catch (Exception e) {
            log.error("[Kafka][payment-completed] Failed to process message: {}", e.getMessage());
        }
    }
}
