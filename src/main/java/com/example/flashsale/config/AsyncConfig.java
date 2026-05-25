package com.example.flashsale.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("cache-evict-");
        executor.initialize();
        return executor;
    }
}
