package com.example.flashsale.service;

import com.example.flashsale.document.UserActionLog;
import com.example.flashsale.repository.jpa.UserRepository;
import com.example.flashsale.repository.mongo.UserActionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Asynchronously writes one UserActionLog document to MongoDB per authenticated request.
 *
 * Step 10 — called by UserActionLogFilter after JwtFilter sets the SecurityContext.
 *
 * Why @Async?
 *   MongoDB writes should never slow down the HTTP response. The log is best-effort;
 *   a failure to write a log entry does not warrant a 500 to the client.
 *
 * Thread pool: reuses "cacheEvictExecutor" (2–8 threads, queue=200) — lightweight enough
 * for simple document inserts alongside the cache-eviction tasks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserActionLogService {

    private final UserRepository userRepository;
    private final UserActionLogRepository userActionLogRepository;

    /**
     * Fire-and-forget: resolves userId from username, then saves to MongoDB.
     * Swallows all exceptions to ensure caller (HTTP thread) is never affected.
     *
     * @param username  principal name from SecurityContextHolder (JWT sub claim)
     * @param action    e.g. "POST /api/orders/checkout"
     * @param ip        remote address from HttpServletRequest
     */
    @Async("cacheEvictExecutor")
    public void logAsync(String username, String action, String ip) {
        try {
            userRepository.findByUsername(username).ifPresent(user -> {
                UserActionLog entry = new UserActionLog();
                entry.setUserId(user.getId());
                entry.setAction(action);
                entry.setTimestamp(LocalDateTime.now());
                entry.setIp(ip);
                userActionLogRepository.save(entry);
            });
        } catch (Exception e) {
            log.warn("[UserActionLog] Failed to write log for user={} action={}: {}",
                    username, action, e.getMessage());
        }
    }
}
