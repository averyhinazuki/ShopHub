package com.example.flashsale.kafka.producer;

import com.example.flashsale.kafka.event.OrderCreatedDomainEvent;
import com.example.flashsale.kafka.event.PaymentCompletedDomainEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Bridges in-process Spring domain events → Kafka topics AFTER the DB transaction commits.
 *
 * Why @TransactionalEventListener(AFTER_COMMIT)?
 *   If a service publishes to Kafka inside a transaction and the transaction later rolls back,
 *   a consumer sees an event for a state that never persisted. AFTER_COMMIT guarantees the DB
 *   write is durable before any downstream system is notified.
 *
 * Step 7 status: STUB — logs only. Actual KafkaTemplate send is wired in Step 9.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventKafkaBridge {

    // Step 9: inject OrderEventProducer here and call sendOrderCreatedEvent / sendPaymentCompletedEvent

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedDomainEvent event) {
        // Step 9: kafkaProducer.sendOrderCreatedEvent(OrderCreatedEvent.builder()
        //             .orderId(event.getOrderId())
        //             .userId(event.getUserId())
        //             .createdAt(event.getCreatedAt())
        //             .build());
        log.info("[Bridge][AFTER_COMMIT] order-created stub: orderId={} userId={}",
                event.getOrderId(), event.getUserId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCompleted(PaymentCompletedDomainEvent event) {
        // Step 9: kafkaProducer.sendPaymentCompletedEvent(PaymentCompletedEvent.builder()
        //             .orderId(event.getOrderId())
        //             .userId(event.getUserId())
        //             .paidAt(event.getPaidAt())
        //             .build());
        log.info("[Bridge][AFTER_COMMIT] payment-completed stub: orderId={} userId={}",
                event.getOrderId(), event.getUserId());
    }
}
