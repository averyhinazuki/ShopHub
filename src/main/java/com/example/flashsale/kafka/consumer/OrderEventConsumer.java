package com.example.flashsale.kafka.consumer;

import com.example.flashsale.config.KafkaTopicConfig;
import com.example.flashsale.document.OrderActivityLog;
import com.example.flashsale.kafka.event.OrderCreatedEvent;
import com.example.flashsale.kafka.event.PaymentCompletedEvent;
import com.example.flashsale.repository.mongo.OrderActivityLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Consumes order-created and payment-completed events from Kafka.
 *
 * Step 9  — receives and logs events; confirms the full publish-after-commit pipeline works.
 * Step 10 — writes OrderActivityLog documents to MongoDB on each event.
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
    private final OrderActivityLogRepository activityLogRepository;

    @KafkaListener(topics = KafkaTopicConfig.ORDER_CREATED_TOPIC, groupId = "flash-sale-group")
    public void handleOrderCreated(String message) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            log.info("[Kafka][order-created] orderId={} userId={} createdAt={}",
                    event.getOrderId(), event.getUserId(), event.getCreatedAt());

            OrderActivityLog entry = new OrderActivityLog();
            entry.setOrderId(event.getOrderId());
            entry.setUserId(event.getUserId());
            entry.setEvent("ORDER_CREATED");
            entry.setTimestamp(event.getCreatedAt() != null ? event.getCreatedAt() : LocalDateTime.now());
            activityLogRepository.save(entry);

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

            OrderActivityLog entry = new OrderActivityLog();
            entry.setOrderId(event.getOrderId());
            entry.setUserId(event.getUserId());
            entry.setEvent("PAYMENT_COMPLETED");
            entry.setTimestamp(event.getPaidAt() != null ? event.getPaidAt() : LocalDateTime.now());
            activityLogRepository.save(entry);

        } catch (Exception e) {
            log.error("[Kafka][payment-completed] Failed to process message: {}", e.getMessage());
        }
    }
}
