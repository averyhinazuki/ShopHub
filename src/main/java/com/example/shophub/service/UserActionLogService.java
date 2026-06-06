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

    @Async("mongoLogExecutor")
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
