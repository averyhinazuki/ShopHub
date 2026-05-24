package com.example.flashsale.kafka.producer;

import com.example.flashsale.kafka.event.OrderCreatedDomainEvent;
import com.example.flashsale.kafka.event.OrderCreatedEvent;
import com.example.flashsale.kafka.event.PaymentCompletedDomainEvent;
import com.example.flashsale.kafka.event.PaymentCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges in-process Spring domain events → Kafka topics AFTER the DB transaction commits.
 *
 * Why @TransactionalEventListener(AFTER_COMMIT)?
 *   Publishing to Kafka inside a transaction risks sending an event for a state that never
 *   persisted (if the transaction rolls back). AFTER_COMMIT guarantees the DB write is
 *   durable before any downstream system is notified.
 *
 *   Residual gap: if the JVM crashes between commit and the Kafka send, the event is lost.
 *   The production fix is the transactional outbox pattern — out of scope for this build.
 *
 * Step 9: fully wired — actual KafkaTemplate sends via OrderEventProducer.
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
