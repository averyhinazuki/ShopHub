package com.example.shophub.kafka.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConsumerIdempotencyGuardTest {

    @Mock StringRedisTemplate            redisTemplate;
    @Mock ValueOperations<String, String> valueOps;
    @InjectMocks ConsumerIdempotencyGuard guard;

    @Test
    void isAlreadyProcessed_returnsFalse_whenKeyAbsent() {
        when(redisTemplate.hasKey("kafka:processed:checkout-requested:abc")).thenReturn(false);
        assertThat(guard.isAlreadyProcessed("checkout-requested", "abc")).isFalse();
    }

    @Test
    void isAlreadyProcessed_returnsTrue_whenKeyPresent() {
        when(redisTemplate.hasKey("kafka:processed:order-created:42")).thenReturn(true);
        assertThat(guard.isAlreadyProcessed("order-created", "42")).isTrue();
    }

    @Test
    void markProcessed_setsKeyWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        guard.markProcessed("checkout-requested", "abc");
        verify(valueOps).set(
                eq("kafka:processed:checkout-requested:abc"),
                eq("1"),
                eq(Duration.ofHours(24)));
    }
}
