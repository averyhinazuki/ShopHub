package com.example.shophub.service;

import com.example.shophub.dto.product.ProductResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Owns all Redis interactions for the product cache.
 *
 * Key: product:{id}:detail (60s TTL) holds the ProductResponse JSON, whose
 * availableStock field is where readers get stock from.
 *
 * Writers use delayed double deletion: deleteCache before the MySQL write, then
 * scheduleSecondDeletion ~500ms after, to evict anything a concurrent reader
 * re-cached mid-write.
 *
 * Kept separate from ProductService so Spring's proxy applies @Async on
 * scheduleSecondDeletion — an intra-bean self-call would bypass it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    static final String DETAIL_KEY = "product:%d:detail";
    static final long   TTL_SECONDS = 60;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // ── Read ────────────────────────────────────────────────────────────────

    public ProductResponse getDetail(Long productId) {
        String json = stringRedisTemplate.opsForValue().get(detailKey(productId));
        if (json == null) return null;
        try {
            return objectMapper.readValue(json, ProductResponse.class);
        } catch (JsonProcessingException e) {
            log.warn("[Cache] Failed to deserialize product:{} detail — evicting", productId);
            deleteCache(productId);
            return null;
        }
    }

    // ── Write ───────────────────────────────────────────────────────────────

    public void setDetail(Long productId, ProductResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(detailKey(productId), json, TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("[Cache] Failed to serialize product:{} — skipping cache write", productId);
        }
    }

    // ── Eviction ────────────────────────────────────────────────────────────

    /** First deletion — called synchronously before the MySQL write. */
    public void deleteCache(Long productId) {
        stringRedisTemplate.delete(detailKey(productId));
        log.debug("[Cache] Evicted product:{} detail", productId);
    }

    /**
     * Second deletion — runs ~500ms after the write on the cacheEvictExecutor
     * pool (returns immediately to the caller), evicting anything a concurrent
     * reader re-cached between the first deletion and the commit.
     */
    @Async("cacheEvictExecutor")
    public void scheduleSecondDeletion(Long productId) {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        deleteCache(productId);
        log.debug("[Cache] Second eviction complete for product:{}", productId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String detailKey(Long id) { return String.format(DETAIL_KEY, id); }
}
