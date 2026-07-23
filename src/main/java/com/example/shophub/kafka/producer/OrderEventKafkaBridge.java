package com.example.shophub.kafka.producer;

import com.example.shophub.kafka.event.OrderCreatedDomainEvent;
import com.example.shophub.kafka.event.OrderCreatedEvent;
import com.example.shophub.kafka.event.PaymentCompletedDomainEvent;
import com.example.shophub.kafka.event.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Forwards in-process domain events to Kafka only after the DB transaction
 * commits (AFTER_COMMIT), so a rolled-back transaction never emits an event for
 * state that didn't persist.
 *
 * Residual gap: a JVM crash between commit and send loses the event — the
 * transactional outbox pattern closes it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventKafkaBridge {

    private final OrderEventProducer kafkaProducer;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedDomainEvent event) {
        log.debug("[Bridge] Forwarding order-created to Kafka: orderId={}", event.getOrderId());
        kafkaProducer.sendOrderCreatedEvent(
                OrderCreatedEvent.builder()
                        .orderId(event.getOrderId())
                        .userId(event.getUserId())
                        .createdAt(event.getCreatedAt())
                        .build()
        );
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCompleted(PaymentCompletedDomainEvent event) {
        log.debug("[Bridge] Forwarding payment-completed to Kafka: orderId={}", event.getOrderId());
        kafkaProducer.sendPaymentCompletedEvent(
                PaymentCompletedEvent.builder()
                        .orderId(event.getOrderId())
                        .userId(event.getUserId())
                        .paidAt(event.getPaidAt())
                        .build()
        );
    }
}
