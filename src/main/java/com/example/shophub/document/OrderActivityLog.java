package com.example.shophub.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Two or three documents per order — ORDER_CREATED, PAYMENT_COMPLETED,
 * EXPIRED_CANCELLED. Low volume, high individual value: this is the lifecycle
 * trail you want during an investigation, so retention is a year rather than the
 * 30 days used for {@link UserActionLog}.
 *
 * Same reasoning as UserActionLog otherwise: unbounded growth here lands on the
 * /data volume MySQL shares, and every natural query (by orderId, by event, by
 * time range) is a full collection scan without these indexes — silently, since
 * MongoDB does not warn about them, and the collection is largest exactly when
 * you need it most.
 *
 * NOTE: none of these indexes exist unless spring.data.mongodb.auto-index-creation
 * is true — Spring Boot 3 defaults it to false, which makes @Indexed inert.
 */
@Data
@Document(collection = "order_activity_log")
@CompoundIndex(name = "ix_order_activity_log_order_ts", def = "{'orderId': 1, 'timestamp': -1}")
public class OrderActivityLog {

    /** 365 days. Annotation values must be compile-time constants, so this cannot come from config. */
    private static final int RETENTION_SECONDS = 365 * 24 * 60 * 60;

    @Id
    private String id;

    @Indexed(name = "ix_order_activity_log_order")
    private Long orderId;

    private Long userId;
    private String event;          // e.g. "ORDER_CREATED", "PAYMENT_COMPLETED", "EXPIRED_CANCELLED"

    // TTL index, and the timestamp index for time-range queries.
    @Indexed(name = "ix_order_activity_log_ttl", expireAfterSeconds = RETENTION_SECONDS)
    private LocalDateTime timestamp;

    private Map<String, Object> metadata;
}
