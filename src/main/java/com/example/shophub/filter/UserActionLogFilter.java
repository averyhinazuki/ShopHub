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
 * Logs each authenticated request to MongoDB (user_action_log).
 *
 * All of the work happens on the *outbound* pass: doFilter is the first
 * statement and the SecurityContext is read only after it returns. So position
 * relative to JwtFilter is not what makes this work — JwtFilter populates the
 * context downstream either way, and this filter would behave identically if
 * registered before it. The real constraint is that it must sit *inside*
 * SecurityContextHolderFilter, which clears the context in a finally block on
 * the way out; outside that region there would be no Authentication left to read.
 *
 * Logs asynchronously (@Async), off the response path. Anonymous requests are
 * skipped — they have no userId. Registered manually in SecurityConfig, not as
 * a @Component, to avoid double registration by Spring Boot.
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
