package com.example.shophub.kafka.consumer;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis-backed dedup guard for Kafka consumers.
 *
 * Key pattern: kafka:processed:{topic}:{messageKey}
 * TTL: 24h — long enough to outlast any realistic retry window.
 *
 * Consumers call isAlreadyProcessed() at the top of their handler and
 * markProcessed() only after successfully completing all side effects.
 * Marking after success means a mid-processing crash will be retried
 * (at-least-once), not silently skipped.
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
