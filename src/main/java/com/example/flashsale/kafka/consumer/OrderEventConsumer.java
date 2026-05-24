package com.example.flashsale.kafka.consumer;

import com.example.flashsale.config.KafkaTopicConfig;
import com.example.flashsale.kafka.event.OrderCreatedEvent;
import com.example.flashsale.kafka.event.PaymentCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Step 10: write order_activity_log to MongoDB on each event
// Step 9 : payment-completed → update order status PENDING → PAID
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopicConfig.ORDER_CREATED_TOPIC, groupId = "flash-sale-group")
    public void handleOrderCreated(String message) {
        try {
            OrderCreatedEvent event = objectMapper.readValue(message, OrderCreatedEvent.class);
            log.info("[Kafka] order-created received: orderId={} userId={}", event.getOrderId(), event.getUserId());
            // TODO Step 10: write to MongoDB order_activity_log
        } catch (Exception e) {
            log.error("[Kafka] Failed to process order-created: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = KafkaTopicConfig.PAYMENT_COMPLETED_TOPIC, groupId = "flash-sale-group")
    public void handlePaymentCompleted(String message) {
        try {
            PaymentCompletedEvent event = objectMapper.readValue(message, PaymentCompletedEvent.class);
            log.info("[Kafka] payment-completed received: orderId={} userId={}", event.getOrderId(), event.getUserId());
            // TODO Step 9 : update Order status PENDING → PAID
            // TODO Step 10: write to MongoDB order_activity_log
        } catch (Exception e) {
            log.error("[Kafka] Failed to process payment-completed: {}", e.getMessage());
        }
    }
}
