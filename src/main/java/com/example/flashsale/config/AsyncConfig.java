package com.example.flashsale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables @Async and configures a dedicated thread pool for background tasks.
 *
 * Used by:
 *  - ProductCacheService.scheduleSecondDeletion()  — delayed cache eviction (~500ms after write)
 *  - UserActionLogFilter (Step 10)                 — async MongoDB write per request
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean(name = "cacheEvictExecutor")
    public Executor cacheEvictExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(32);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("cache-evict-");
        // CallerRunsPolicy: if pool saturates, run on the caller thread instead of dropping.
        // Trades request latency for guaranteed delivery of cache evictions and audit logs.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
