package com.example.flashsale.kafka.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;

/**
 * In-process Spring event published inside the /pay @Transactional.
 * The OrderEventKafkaBridge listens with @TransactionalEventListener(AFTER_COMMIT)
 * and forwards to Kafka ONLY after the DB transaction commits.
 */
@Getter
public class PaymentCompletedDomainEvent extends ApplicationEvent {

    private final Long orderId;
    private final Long userId;
    private final LocalDateTime paidAt;

    public PaymentCompletedDomainEvent(Object source, Long orderId, Long userId, LocalDateTime paidAt) {
        super(source);
        this.orderId = orderId;
        this.userId  = userId;
        this.paidAt  = paidAt;
    }
}
