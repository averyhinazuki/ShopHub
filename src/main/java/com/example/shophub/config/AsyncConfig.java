package com.example.shophub.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    /**
     * Delayed second cache deletions (see ProductCacheService).
     *
     * A scheduler, not a worker pool. The previous design handed the task to an
     * @Async executor which then slept 500ms, so each deletion held a thread for
     * half a second — 2 tasks/thread/sec, ~16/sec across 8 core threads, against
     * a 2000-deep queue the pool would effectively never grow past core size to
     * drain. Here the delay costs no thread at all, so capacity becomes "how fast
     * can Redis accept DELETEs", which is enormous. Pool size 4 is for
     * concurrency of the *deletes*, not of the waiting.
     */
    @Bean(name = "cacheEvictScheduler")
    public TaskScheduler cacheEvictScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("cache-evict-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.initialize();
        return scheduler;
    }

    /**
     * The scheduler @Scheduled runs on — OrderExpiryScheduler today.
     *
     * Declared explicitly because ScheduledAnnotationBeanPostProcessor resolves a
     * TaskScheduler *by type*: introducing cacheEvictScheduler above as the only
     * one would have silently moved the expiry job onto it. With two beans present
     * the by-type lookup is ambiguous and Spring falls back to the bean literally
     * named "taskScheduler", so this pairing is deterministic rather than lucky.
     */
    @Bean(name = "taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.initialize();
        return scheduler;
    }

    @Bean(name = "mongoLogExecutor")
    public Executor mongoLogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("mongo-log-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }
}
