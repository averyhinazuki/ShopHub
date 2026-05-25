package com.example.flashsale.scheduler;

import com.example.flashsale.document.OrderActivityLog;
import com.example.flashsale.entity.Order;
import com.example.flashsale.enums.OrderStatus;
import com.example.flashsale.repository.jpa.OrderItemRepository;
import com.example.flashsale.repository.jpa.OrderRepository;
import com.example.flashsale.repository.jpa.ProductInventoryRepository;
import com.example.flashsale.repository.mongo.OrderActivityLogRepository;
import com.example.flashsale.service.ProductCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scans for PENDING orders older than {@code app.order.pending-timeout-minutes} and
 * cancels them, restoring their stock — so abandoned carts don't hold stock forever.
 *
 * Step 11.
 *
 * Race guard:
 *   {@code cancelIfPending} is a conditional UPDATE:
 *     UPDATE orders SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'
 *   If /pay wins first → rowsAffected = 0 → scheduler skips; stock stays deducted (correct).
 *   If scheduler wins first → /pay later gets rowsAffected = 0 → 409 to the user (correct).
 *   Exactly one of the two commits; never both.
 *
 * Stock restoration uses the SAME Redisson lock as checkout and admin inventory PATCH
 * (lock:product:{id}) so it cannot race with a concurrent checkout on the same product.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderExpiryScheduler {

    @Value("${app.order.pending-timeout-minutes}")
    private int pendingTimeoutMinutes;

    @Value("${app.order.expiry-job-batch-size}")
    private int batchSize;

    private final OrderRepository            orderRepository;
    private final OrderItemRepository        orderItemRepository;
    private final ProductInventoryRepository inventoryRepository;
    private final ProductCacheService        cacheService;
    private final RedissonClient             redissonClient;
    private final OrderActivityLogRepository activityLogRepository;

    /**
     * Runs every {@code app.order.expiry-job-interval-seconds} seconds (fixed delay —
     * next run starts after the current run finishes, preventing overlapping executions).
     *
     * Initial delay of 30 s gives the application time to fully start before
     * the first scan.
     */
    @Scheduled(
        fixedDelayString  = "#{${app.order.expiry-job-interval-seconds} * 1000}",
        initialDelayString = "30000"
    )
    public void cancelExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(pendingTimeoutMinutes);

        List<Long> expiredIds = orderRepository.findExpiredOrderIds(
                OrderStatus.PENDING, cutoff, PageRequest.of(0, batchSize));

        if (expiredIds.isEmpty()) {
            return; // nothing to do — skip noisy log
        }

        log.info("[Expiry] Found {} expired PENDING order(s) — processing", expiredIds.size());

        for (Long orderId : expiredIds) {
            try {
                processSingleOrder(orderId);
            } catch (Exception e) {
                // One bad order must never abort the whole batch
                log.error("[Expiry] Failed to process orderId={}: {}", orderId, e.getMessage());
            }
        }
    }

    // ── Per-order logic ───────────────────────────────────────────────────────

    private void processSingleOrder(Long orderId) {

        // Step a — atomically claim the cancellation.
        // cancelIfPending is @Transactional; it commits immediately.
        int rows = orderRepository.cancelIfPending(orderId);
        if (rows == 0) {
            // /pay already committed — do nothing; stock was legitimately sold.
            log.debug("[Expiry] orderId={} already paid or cancelled — skipping", orderId);
            return;
        }

        log.info("[Expiry] Cancelled orderId={}", orderId);

        // Step b — load userId (for activity log) and item data (for stock restore)
        // These are read-only queries; Spring Data creates short transactions automatically.
        Long userId = orderRepository.findById(orderId)
                .map(Order::getUserId)
                .orElse(null);

        // Step c — projection query: avoids lazy-loading the Product entity
        List<Object[]> items = orderItemRepository.findProductIdAndQuantityByOrderId(orderId);

        // Step d — restore stock for each item under Redisson lock
        for (Object[] row : items) {
            Long productId = (Long) row[0];
            int  qty       = ((Number) row[1]).intValue();

            restoreStockForItem(orderId, productId, qty);
        }

        // Step e — write activity log to MongoDB (best-effort, never fails the job)
        try {
            OrderActivityLog entry = new OrderActivityLog();
            entry.setOrderId(orderId);
            entry.setUserId(userId);
            entry.setEvent("EXPIRED_CANCELLED");
            entry.setTimestamp(LocalDateTime.now());
            activityLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("[Expiry] Failed to write activity log for orderId={}: {}", orderId, e.getMessage());
        }
    }

    /**
     * Acquires the per-product Redisson lock (same key used by checkout and admin PATCH),
     * performs cache-aside invalidation, restores stock in MySQL, then schedules the
     * async second cache deletion.
     *
     * If the lock cannot be acquired (e.g. a checkout is in progress), logs a warning
     * and skips — the stock for this item is not restored. In production this would be
     * addressed by a retry queue; acceptable for this build.
     */
    private void restoreStockForItem(Long orderId, Long productId, int qty) {
        RLock lock = redissonClient.getLock("lock:product:" + productId);
        try {
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[Expiry] Could not acquire lock for productId={} — stock NOT restored for orderId={}",
                        productId, orderId);
                return;
            }

            // First cache deletion — before the MySQL write
            cacheService.deleteCache(productId);

            // MySQL stock restore (@Transactional on repo method — commits immediately)
            inventoryRepository.restoreStock(productId, qty);

            lock.unlock();

            // Async second deletion — kills any stale entry re-cached by a reader
            // in the window between the first deletion and the MySQL commit
            cacheService.scheduleSecondDeletion(productId);

            log.debug("[Expiry] Restored productId={} qty={} for orderId={}", productId, qty, orderId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (lock.isHeldByCurrentThread()) lock.unlock();
            log.error("[Expiry] Lock wait interrupted for productId={}", productId);
        } catch (Exception e) {
            if (lock.isHeldByCurrentThread()) lock.unlock();
            log.error("[Expiry] Error restoring stock for productId={}: {}", productId, e.getMessage());
        }
    }
}
