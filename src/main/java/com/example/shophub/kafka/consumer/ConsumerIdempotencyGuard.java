package com.example.shophub.kafka.consumer;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed dedup guard for Kafka consumers. Keys are
 * kafka:processed:{topic}:{messageKey} with a 24h TTL (outlasts any retry window).
 *
 * Consumers check isAlreadyProcessed() first and call markProcessed() only after
 * all side effects succeed, so a mid-processing crash is retried (at-least-once)
 * rather than silently dropped.
 */
@Component
@RequiredArgsConstructor
public class ConsumerIdempotencyGuard {

    private static final Duration TTL = Duration.ofHours(24);
    private final StringRedisTemplate redisTemplate;

    public boolean isAlreadyProcessed(String topic, String messageKey) {
        return Boolean.TRUE.equals(redisTemplate.hasKey("kafka:processed:" + topic + ":" + messageKey));
    }

    public void markProcessed(String topic, String messageKey) {
        redisTemplate.opsForValue().set("kafka:processed:" + topic + ":" + messageKey, "1", TTL);
    }
}
