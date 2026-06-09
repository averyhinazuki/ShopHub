package com.example.shophub.kafka.consumer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.document.OrderActivityLog;
import com.example.shophub.kafka.event.OrderCreatedEvent;
import com.example.shophub.kafka.event.PaymentCompletedEvent;
import com.example.shophub.repository.mongo.OrderActivityLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Consumes order-created and payment-completed events from Kafka and writes
 * OrderActivityLog documents to MongoDB for downstream audit/analytics.
 *
 * Idempotency: duplicate deliveries (same orderId key) are skipped via Redis dedup.
 * Malformed JSON: logged and skipped — no retry on poison pills.
 * Other exceptions propagate so the Kafka error handler can decide (retry/DLT added in M3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ObjectMapper               objectMapper;
    private final OrderActivityLogRepository activityLogRepository;
    private final ConsumerIdempotencyGuard   idempotencyGuard;

    @KafkaListener(topics = KafkaTopicConfig.ORDER_CREATED_TOPIC, groupId = "flash-sale-group")
    public void handleOrderCreated(String message, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.ORDER_CREATED_TOPIC, key)) {
            log.warn("[Kafka][order-created] Duplicate key={} — skipping", key);
            return;
        }

        OrderCreatedEvent event;
        try {
            event = objectMapper.readValue(message, OrderCreatedEvent.class);
        } catch (Exception e) {
            log.error("[Kafka][order-created] Malformed message — skipping: {}", e.getMessage());
            return;
        }

        log.info("[Kafka][order-created] orderId={} userId={} createdAt={}",
                event.getOrderId(), event.getUserId(), event.getCreatedAt());

        OrderActivityLog entry = new OrderActivityLog();
        entry.setOrderId(event.getOrderId());
        entry.setUserId(event.getUserId());
        entry.setEvent("ORDER_CREATED");
        entry.setTimestamp(event.getCreatedAt() != null ? event.getCreatedAt() : LocalDateTime.now());
        activityLogRepository.save(entry);

        idempotencyGuard.markProcessed(KafkaTopicConfig.ORDER_CREATED_TOPIC, key);
    }

    @KafkaListener(topics = KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, groupId = "flash-sale-group")
    public void handlePaymentCompleted(String message, @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (idempotencyGuard.isAlreadyProcessed(KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, key)) {
            log.warn("[Kafka][payment-completed] Duplicate key={} — skipping", key);
            return;
        }

        PaymentCompletedEvent event;
        try {
            event = objectMapper.readValue(message, PaymentCompletedEvent.class);
        } catch (Exception e) {
            log.error("[Kafka][payment-completed] Malformed message — skipping: {}", e.getMessage());
            return;
        }

        log.info("[Kafka][payment-completed] orderId={} userId={} paidAt={}",
                event.getOrderId(), event.getUserId(), event.getPaidAt());

        OrderActivityLog entry = new OrderActivityLog();
        entry.setOrderId(event.getOrderId());
        entry.setUserId(event.getUserId());
        entry.setEvent("PAYMENT_COMPLETED");
        entry.setTimestamp(event.getPaidAt() != null ? event.getPaidAt() : LocalDateTime.now());
        activityLogRepository.save(entry);

        idempotencyGuard.markProcessed(KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, key);
    }
}
