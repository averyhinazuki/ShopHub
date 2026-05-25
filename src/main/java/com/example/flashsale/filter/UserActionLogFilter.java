package com.example.flashsale.filter;

import com.example.flashsale.service.UserActionLogService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Logs each authenticated HTTP request to MongoDB (user_action_log collection).
 *
 * Step 10 — registered inside the Spring Security filter chain, AFTER JwtFilter,
 * so SecurityContextHolder already contains the resolved Authentication when this runs.
 *
 * Design decisions:
 *  - Runs the filter chain FIRST, then writes the log. This means the log entry is written
 *    regardless of the response status, but after the response is committed (fire-and-forget
 *    via @Async — no impact on response latency).
 *  - Anonymous / unauthenticated requests are skipped — public endpoints (product browsing)
 *    don't have a userId to associate.
 *  - NOT a @Component — registered manually in SecurityConfig to avoid Spring Boot's
 *    auto-registration which would cause the filter to fire twice per request.
 */
@Slf4j
@RequiredArgsConstructor
public class UserActionLogFilter extends OncePerRequestFilter {

    private final UserActionLogService userActionLogService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        // Let the rest of the chain (controllers, etc.) execute first
        filterChain.doFilter(request, response);

        // After response is ready — check if the request was authenticated
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)) {

            String username = auth.getName();
            String action   = request.getMethod() + " " + request.getRequestURI();
            String ip       = request.getRemoteAddr();

            // Fire-and-forget — MongoDB write on cacheEvictExecutor thread pool
            userActionLogService.logAsync(username, action, ip);
        }
    }
}
