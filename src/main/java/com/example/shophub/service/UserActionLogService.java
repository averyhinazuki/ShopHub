package com.example.shophub.service;

import com.example.shophub.document.UserActionLog;
import com.example.shophub.repository.jpa.UserRepository;
import com.example.shophub.repository.mongo.UserActionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserActionLogService {

    private final UserRepository userRepository;
    private final UserActionLogRepository userActionLogRepository;

    /**
     * userId comes from the token's uid claim, so the common path does no query at
     * all. It used to repeat the same findByUsername that SecurityUtils had already
     * done on the request thread — one request resolving the identical
     * username -> userId mapping from MySQL twice, once per thread.
     *
     * The username fallback covers access tokens minted before the uid claim; those
     * expire within minutes of a deploy. Doing it here rather than in the filter
     * keeps the lookup off the request thread, which is the whole point of this
     * being @Async.
     */
    @Async("mongoLogExecutor")
    public void logAsync(Long userId, String username, String action, String ip) {
        try {
            Long resolvedUserId = userId != null
                    ? userId
                    : userRepository.findByUsername(username).map(u -> u.getId()).orElse(null);
            if (resolvedUserId == null) {
                return;   // token subject no longer exists — nothing to attribute the action to
            }

            UserActionLog entry = new UserActionLog();
            entry.setUserId(resolvedUserId);
            entry.setAction(action);
            entry.setTimestamp(LocalDateTime.now());
            entry.setIp(ip);
            userActionLogRepository.save(entry);
        } catch (Exception e) {
            log.warn("[UserActionLog] Failed to write log for user={} action={}: {}",
                    username, action, e.getMessage());
        }
    }
}
