package com.example.shophub.kafka.producer;

import com.example.shophub.config.KafkaTopicConfig;
import com.example.shophub.kafka.event.CheckoutRequestedEvent;
import com.example.shophub.kafka.event.OrderCreatedEvent;
import com.example.shophub.kafka.event.PaymentCompletedEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderEventProducer {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

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
                }
            });
        } catch (JsonProcessingException e) {
            log.error("[Kafka] Serialization error for CheckoutRequestedEvent: {}", e.getMessage());
        }
    }
}
