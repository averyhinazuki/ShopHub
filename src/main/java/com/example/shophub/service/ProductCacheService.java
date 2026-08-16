package com.example.shophub.service;

import com.example.shophub.dto.product.ProductResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
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
 * Every method here degrades rather than throws when Redis is unreachable. That
 * is the whole point of cache-aside: MySQL is the source of truth and the cache
 * is optional, so an outage must make product pages *slower*, never unavailable.
 * Note this is the one Redis dependency that is genuinely optional — the Redisson
 * locks, checkout status, dedup guard and refresh tokens are all real dependencies.
 *
 * Kept separate from ProductService because it owns one thing — the Redis side of
 * the product cache. (It used to need separating so Spring's proxy could apply
 * @Async to scheduleSecondDeletion; that reason no longer applies now the delay
 * goes through a TaskScheduler instead.)
 */
@Slf4j
@Service
public class ProductCacheService {

    static final String DETAIL_KEY = "product:%d:detail";
    static final long   TTL_SECONDS = 60;
    static final long   SECOND_DELETION_DELAY_MS = 500;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final TaskScheduler cacheEvictScheduler;

    // Explicit constructor rather than @RequiredArgsConstructor: Lombok does not
    // copy @Qualifier onto generated constructor parameters unless configured to,
    // and there are two TaskScheduler beans. Silently binding to the wrong one is
    // exactly the kind of failure that shows up only under load.
    public ProductCacheService(StringRedisTemplate stringRedisTemplate,
                               ObjectMapper objectMapper,
                               @Qualifier("cacheEvictScheduler") TaskScheduler cacheEvictScheduler) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.cacheEvictScheduler = cacheEvictScheduler;
    }

    // ── Read ────────────────────────────────────────────────────────────────

    /** Returns null on a miss — and treats an unreachable Redis as a miss. */
    public ProductResponse getDetail(Long productId) {
        String json;
        try {
            json = stringRedisTemplate.opsForValue().get(detailKey(productId));
        } catch (RuntimeException e) {
            log.warn("[Cache] Redis unavailable reading product:{} — treating as a miss: {}",
                    productId, e.getMessage());
            return null;
        }
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
        } catch (RuntimeException e) {
            log.warn("[Cache] Redis unavailable writing product:{} — skipping cache write: {}",
                    productId, e.getMessage());
        }
    }

    // ── Eviction ────────────────────────────────────────────────────────────

    /**
     * First deletion — called synchronously before the MySQL write.
     *
     * A failure here is logged, not thrown: the entry carries a 60s TTL, so the
     * worst case is bounded staleness, whereas propagating would fail the write
     * that the eviction exists to protect.
     */
    public void deleteCache(Long productId) {
        try {
            stringRedisTemplate.delete(detailKey(productId));
            log.debug("[Cache] Evicted product:{} detail", productId);
        } catch (RuntimeException e) {
            log.warn("[Cache] Redis unavailable evicting product:{} — entry will expire via its {}s TTL: {}",
                    productId, TTL_SECONDS, e.getMessage());
        }
    }

    /**
     * Second deletion — fires ~500ms after the write, evicting anything a
     * concurrent reader re-cached between the first deletion and the commit.
     * Returns immediately.
     *
     * The delay is scheduled, not slept. Sleeping held a worker thread for the
     * full 500ms, which capped throughput at roughly 16 deletions/sec across the
     * 8 core threads — and because the pool only grows past core size once its
     * 2000-deep queue is full, it would queue rather than add threads. Under load
     * the backlog grew and second deletions ran minutes late, which does not merely
     * waste threads: it defeats the mechanism. This eviction exists to close a race
     * window measured in milliseconds around the write, so firing it two minutes
     * later evicts a long-since-correct entry while leaving the actual stale window
     * uncovered. A flash sale at 100 checkouts/sec x 2 items needs 200 deletions/sec.
     */
    public void scheduleSecondDeletion(Long productId) {
        cacheEvictScheduler.schedule(
                () -> deleteCache(productId),
                Instant.now().plusMillis(SECOND_DELETION_DELAY_MS));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private String detailKey(Long id) { return String.format(DETAIL_KEY, id); }
}
