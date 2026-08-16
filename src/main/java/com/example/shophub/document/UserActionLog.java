package com.example.shophub.document;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One document per authenticated request. High volume, low individual value.
 *
 * Retention is 30 days, enforced by the TTL index on {@code timestamp}. Without it
 * this collection grows forever on the same 10 GiB /data volume MySQL bind-mounts,
 * so an audit log nobody reads can fill the disk and stop the transactional
 * database writing — taking the whole site down. MongoDB removes expired documents
 * itself; no job required.
 *
 * The TTL works because Spring Data stores LocalDateTime as a BSON date. A TTL
 * index on a string field is accepted silently and never deletes anything.
 *
 * NOTE: none of these indexes exist unless spring.data.mongodb.auto-index-creation
 * is true — Spring Boot 3 defaults it to false, which makes @Indexed inert.
 */
@Data
@Document(collection = "user_action_log")
@CompoundIndex(name = "ix_user_action_log_user_ts", def = "{'userId': 1, 'timestamp': -1}")
public class UserActionLog {

    /** 30 days. Annotation values must be compile-time constants, so this cannot come from config. */
    private static final int RETENTION_SECONDS = 30 * 24 * 60 * 60;

    @Id
    private String id;

    private Long userId;
    private String action;         // e.g. "GET /api/products", "POST /api/orders/checkout"

    // TTL index. Also serves as the plain timestamp index for "what happened around
    // 3am" queries; the compound index above covers "what did user 42 do recently".
    @Indexed(name = "ix_user_action_log_ttl", expireAfterSeconds = RETENTION_SECONDS)
    private LocalDateTime timestamp;

    private String ip;
}
