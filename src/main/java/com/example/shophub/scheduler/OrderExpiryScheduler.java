package com.example.shophub.scheduler;

import com.example.shophub.document.OrderActivityLog;
import com.example.shophub.entity.Order;
import com.example.shophub.enums.OrderStatus;
import com.example.shophub.repository.jpa.OrderItemRepository;
import com.example.shophub.repository.jpa.OrderRepository;
import com.example.shophub.repository.jpa.ProductInventoryRepository;
import com.example.shophub.repository.mongo.OrderActivityLogRepository;
import com.example.shophub.service.ProductCacheService;
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
 * Cancels PENDING orders older than {@code app.order.pending-timeout-minutes} and
 * restores their stock, so abandoned checkouts don't hold stock indefinitely.
 *
 * {@code cancelIfPending} is a conditional UPDATE guarded on status = 'PENDING',
 * so it and /pay can never both win: whichever commits first leaves the other
 * with rows=0 (scheduler skips, or /pay returns 409).
 *
 * Restoration takes the same lock:product:{id} Redisson lock as checkout and the
 * admin inventory PATCH, so it cannot race a concurrent checkout on that product.
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
     * Fixed-delay scan (no overlap between runs), every
     * {@code app.order.expiry-job-interval-seconds} seconds, after a 30s startup delay.
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
            return;
        }

        log.info("[Expiry] Found {} expired PENDING order(s) — processing", expiredIds.size());

        for (Long orderId : expiredIds) {
            try {
                processSingleOrder(orderId);
            } catch (Exception e) {
                // Isolate failures so one bad order doesn't abort the batch.
                log.error("[Expiry] Failed to process orderId={}: {}", orderId, e.getMessage());
            }
        }
    }

    // ── Per-order logic ───────────────────────────────────────────────────────

    private void processSingleOrder(Long orderId) {
        // rows=0 means /pay already committed; the stock was legitimately sold.
        int rows = orderRepository.cancelIfPending(orderId);
        if (rows == 0) {
            log.debug("[Expiry] orderId={} already paid or cancelled — skipping", orderId);
            return;
        }

        log.info("[Expiry] Cancelled orderId={}", orderId);

        Long userId = orderRepository.findById(orderId)
                .map(Order::getUserId)
                .orElse(null);

        // Projection avoids lazy-loading full Product entities.
        List<Object[]> items = orderItemRepository.findProductIdAndQuantityByOrderId(orderId);

        for (Object[] row : items) {
            Long productId = (Long) row[0];
            int  qty       = ((Number) row[1]).intValue();

            restoreStockForItem(orderId, productId, qty);
        }

        // Best-effort activity log — a failure here must not abort the job.
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
     * Under lock:product:{id} (shared with checkout and the admin PATCH), evicts the
     * cache, restores stock in MySQL, and schedules the async second deletion.
     *
     * If the lock can't be acquired the item is skipped and its stock is not
     * restored — a retry queue would close this gap; left out here by design.
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

            cacheService.deleteCache(productId); // first deletion, before the write
            inventoryRepository.restoreStock(productId, qty);
            lock.unlock();

            // Second deletion clears anything a reader re-cached during the write.
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
