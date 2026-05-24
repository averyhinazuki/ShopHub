package com.example.flashsale.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Manages stateful refresh tokens in Redis.
 *
 * Key pattern: refresh:{jti} → userId (string)
 * TTL mirrors the token's expiry so Redis self-cleans expired entries.
 *
 * On /logout  → revoke(jti): deletes the key immediately
 * On /refresh → revoke(old jti) then store(new jti): rotation
 * Absence of key = revoked or naturally expired → 401
 */
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";

    private final StringRedisTemplate stringRedisTemplate;

    public void store(String jti, Long userId, long ttlMs) {
        stringRedisTemplate.opsForValue().set(
                KEY_PREFIX + jti,
                userId.toString(),
                ttlMs,
                TimeUnit.MILLISECONDS
        );
    }

    /** Returns the userId if the token is still valid, null if revoked or expired. */
    public Long lookup(String jti) {
        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + jti);
        return value != null ? Long.parseLong(value) : null;
    }

    public void revoke(String jti) {
        stringRedisTemplate.delete(KEY_PREFIX + jti);
    }
}
