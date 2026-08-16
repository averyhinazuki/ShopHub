package com.example.shophub.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counters for the things that actually go wrong in this system.
 *
 * The Grafana dashboard measures infrastructure thoroughly — HTTP rate/latency/
 * errors, JVM heap, GC, threads, CPU, HikariCP depth, Kafka consumer lag, host
 * disk/CPU/memory — and no domain outcome at all. So every serious defect found
 * while studying this codebase would have been invisible in production while the
 * dashboard stayed green: checkouts reaching the DLT, stock restorations
 * abandoned, statuses silently lost.
 *
 * All counters are registered here in the constructor rather than lazily at each
 * call site, and that is load-bearing rather than tidiness. **A counter that has
 * never incremented produces no time series at all**, so a rule querying it reads
 * NoData rather than zero. That is exactly what left the p99 alert resting in
 * DatasourceNoData (PR #9). Registering up front means every series exists at 0
 * from application startup and the alerts read what they are meant to read.
 *
 * Prometheus names, after Micrometer's naming convention:
 *   shophub_checkout_total{outcome="success"|"sold_out"}
 *   shophub_checkout_dlt_total
 *   shophub_checkout_status_write_failed_total
 *   shophub_stock_restore_failed_total{source="checkout"|"expiry", reason=...}
 */
@Component
public class DomainMetrics {

    private final Counter checkoutSuccess;
    private final Counter checkoutSoldOut;
    private final Counter checkoutDlt;
    private final Counter checkoutStatusWriteFailed;
    private final Counter restoreFailedCheckout;
    private final Counter restoreFailedExpiryLockTimeout;
    private final Counter restoreFailedExpiryError;

    public DomainMetrics(MeterRegistry registry) {
        this.checkoutSuccess = Counter.builder("shophub.checkout")
                .description("Checkouts processed, by outcome")
                .tag("outcome", "success")
                .register(registry);
        this.checkoutSoldOut = Counter.builder("shophub.checkout")
                .description("Checkouts processed, by outcome")
                .tag("outcome", "sold_out")
                .register(registry);
        this.checkoutDlt = Counter.builder("shophub.checkout.dlt")
                .description("Checkout messages that exhausted all retries and reached the dead-letter topic")
                .register(registry);
        this.checkoutStatusWriteFailed = Counter.builder("shophub.checkout.status.write.failed")
                .description("Checkout status records that could not be written to Redis")
                .register(registry);
        this.restoreFailedCheckout = Counter.builder("shophub.stock.restore.failed")
                .description("Stock restorations abandoned — inventory now disagrees with orders")
                .tags("source", "checkout", "reason", "error")
                .register(registry);
        this.restoreFailedExpiryLockTimeout = Counter.builder("shophub.stock.restore.failed")
                .description("Stock restorations abandoned — inventory now disagrees with orders")
                .tags("source", "expiry", "reason", "lock_timeout")
                .register(registry);
        this.restoreFailedExpiryError = Counter.builder("shophub.stock.restore.failed")
                .description("Stock restorations abandoned — inventory now disagrees with orders")
                .tags("source", "expiry", "reason", "error")
                .register(registry);
    }

    /** A shift in the success/sold_out ratio is the earliest signal of most of the known defects. */
    public void checkoutSucceeded()  { checkoutSuccess.increment(); }
    public void checkoutSoldOut()    { checkoutSoldOut.increment(); }

    /** Anything here exhausted retries and nobody has looked at it. Alerts at > 0. */
    public void checkoutReachedDlt() { checkoutDlt.increment(); }

    /** The F1 path: order committed, status lost. The customer cannot see their result. */
    public void checkoutStatusWriteFailed() { checkoutStatusWriteFailed.increment(); }

    /**
     * F13 — checkout's compensation loop gave up on a restore. Stock is permanently
     * lost: the database believes fewer units exist than physically do, and repeated
     * occurrences shrink inventory monotonically until products read "sold out"
     * while sitting on the shelf.
     */
    public void stockRestoreFailedInCheckout() { restoreFailedCheckout.increment(); }

    /**
     * F2 — the expiry job cancelled an order but could not take the product lock, so
     * its stock was never restored. The gap is documented as deliberate; what was
     * missing is any way to find out it had been hit. Per item, so a three-line order
     * can be partially restored.
     */
    public void stockRestoreFailedInExpiryLockTimeout() { restoreFailedExpiryLockTimeout.increment(); }

    /** F2's other branch: the lock was taken but the restore itself threw. */
    public void stockRestoreFailedInExpiryError() { restoreFailedExpiryError.increment(); }
}
