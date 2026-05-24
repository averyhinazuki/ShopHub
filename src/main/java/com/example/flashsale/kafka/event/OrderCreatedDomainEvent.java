package com.example.flashsale.kafka.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * In-process Spring event published inside the checkout @Transactional.
 * The OrderEventKafkaBridge listens with @TransactionalEventListener(AFTER_COMMIT)
 * and forwards to Kafka ONLY after the DB transaction commits — so a rollback
 * guarantees no phantom Kafka message is sent.
 */
@Getter
public class OrderCreatedDomainEvent extends ApplicationEvent {

    private final Long orderId;
    private final Long userId;
    private final LocalDateTime createdAt;

    public OrderCreatedDomainEvent(Object source, Long orderId, Long userId, LocalDateTime createdAt) {
        super(source);
        this.orderId   = orderId;
        this.userId    = userId;
        this.createdAt = createdAt;
    }
}
