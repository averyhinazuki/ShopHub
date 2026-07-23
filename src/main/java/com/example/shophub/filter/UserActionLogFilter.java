package com.example.shophub.filter;

import com.example.shophub.service.UserActionLogService;
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
 * Logs each authenticated request to MongoDB (user_action_log). Sits after
 * JwtFilter, so the Authentication is already resolved when it runs.
 *
 * Runs the chain first, then logs asynchronously (@Async), off the response path.
 * Anonymous requests are skipped — they have no userId. Registered manually in
 * SecurityConfig, not as a @Component, to avoid double registration by Spring Boot.
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

        filterChain.doFilter(request, response);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null
                && auth.isAuthenticated()
                && !(auth instanceof AnonymousAuthenticationToken)) {

            String username = auth.getName();
            String action   = request.getMethod() + " " + request.getRequestURI();
            String ip       = request.getRemoteAddr();

            userActionLogService.logAsync(username, action, ip);
        }
    }
}
