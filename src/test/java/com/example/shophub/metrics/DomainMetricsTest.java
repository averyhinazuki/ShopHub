package com.example.shophub.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DomainMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final DomainMetrics metrics = new DomainMetrics(registry);

    /**
     * The load-bearing property. A counter that has never incremented produces NO
     * time series, so a rule querying it reads NoData rather than 0 — which is
     * exactly what left the p99 alert resting in DatasourceNoData (PR #9).
     * Registering every counter in the constructor means the series exist at zero
     * from startup, so "nothing has gone wrong" is reported as 0 and not as absence.
     */
    @Test
    void everyCounterExistsAtZeroBeforeAnythingHappens() {
        assertThat(registry.find("shophub.checkout").tag("outcome", "success").counter()).isNotNull();
        assertThat(registry.find("shophub.checkout").tag("outcome", "sold_out").counter()).isNotNull();
        assertThat(registry.find("shophub.checkout.dlt").counter()).isNotNull();
        assertThat(registry.find("shophub.checkout.status.write.failed").counter()).isNotNull();
        assertThat(registry.find("shophub.stock.restore.failed")
                .tags("source", "checkout", "reason", "error").counter()).isNotNull();
        assertThat(registry.find("shophub.stock.restore.failed")
                .tags("source", "expiry", "reason", "lock_timeout").counter()).isNotNull();
        assertThat(registry.find("shophub.stock.restore.failed")
                .tags("source", "expiry", "reason", "error").counter()).isNotNull();

        assertThat(registry.find("shophub.checkout.dlt").counter().count()).isZero();
    }

    @Test
    void countersIncrementIndependently() {
        metrics.checkoutSucceeded();
        metrics.checkoutSucceeded();
        metrics.checkoutSoldOut();
        metrics.stockRestoreFailedInExpiryLockTimeout();

        assertThat(registry.get("shophub.checkout").tag("outcome", "success").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("shophub.checkout").tag("outcome", "sold_out").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("shophub.stock.restore.failed")
                .tags("source", "expiry", "reason", "lock_timeout").counter().count()).isEqualTo(1.0);
        // Different tag set, same metric name — must not have been incremented.
        assertThat(registry.get("shophub.stock.restore.failed")
                .tags("source", "checkout", "reason", "error").counter().count()).isZero();
    }
}
