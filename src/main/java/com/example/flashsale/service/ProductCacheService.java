package com.example.flashsale.service;

import com.example.flashsale.dto.product.ProductResponse;
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
 * Keys (both share TTL = 60s so they expire together):
 *   product:{id}:detail  →  full ProductResponse JSON
 *   product:{id}:stock   →  availableStock integer string
 *
 * Delayed double deletion pattern (called by ProductService on every write):
 *   1. deleteCache(id)                ← first deletion  (before MySQL write)
 *   2. MySQL write happens
 *   3. scheduleSecondDeletion(id)     ← async second deletion ~500ms later
 *      Kills any stale entry a concurrent reader cached between steps 1 and 2.
 *
 * This bean MUST be a separate Spring component from ProductService so that
 * Spring's proxy intercepts the @Async annotation on scheduleSecondDeletion().
 * Self-calls within the same bean bypass the proxy and @Async has no effect.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCacheService {

    static final String DETAIL_KEY = "product:%d:detail";
    static final String STOCK_KEY  = "product:%d:stock";
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

    public Integer getStock(Long productId) {
        String val = stringRedisTemplate.opsForValue().get(stockKey(productId));
        return val != null ? Integer.parseInt(val) : null;
    }

    // ── Write ───────────────────────────────────────────────────────────────

    public void setDetail(Long productId, ProductResponse response) {
        try {
            String json = objectMapper.writeValueAsString(response);
            stringRedisTemplate.opsForValue().set(detailKey(productId), json, TTL_SECONDS, TimeUnit.SECONDS);
            stringRedisTemplate.opsForValue().set(stockKey(productId),
                    response.getAvailableStock().toString(), TTL_SECONDS, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("[Cache] Failed to serialize product:{} — skipping cache write", productId);
        }
    }

    // ── Eviction ────────────────────────────────────────────────────────────

    /** First deletion — called synchronously before the MySQL write. */
    public void deleteCache(Long productId) {
        stringRedisTemplate.delete(detailKey(productId));
        stringRedisTemplate.delete(stockKey(productId));
        log.debug("[Cache] Evicted product:{} (detail + stock)", productId);
    }

    /**
     * Second deletion — fires ~500ms after the MySQL write on a background thread.
     * Kills any stale value a concurrent reader cached between the first deletion
     * and the MySQL commit.
     *
     * @Async("cacheEvictExecutor") means this returns immediately to the caller;
     * the sleep + delete run on the cacheEvictExecutor thread pool.
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
    private String stockKey(Long id)  { return String.format(STOCK_KEY,  id); }
}
