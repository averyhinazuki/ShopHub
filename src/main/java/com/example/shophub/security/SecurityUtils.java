package com.example.shophub.security;

import com.example.shophub.exception.UnauthorizedException;
import com.example.shophub.repository.jpa.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityUtils {

    private final UserRepository userRepository;

    /**
     * The caller's user id, from the token's uid claim — no query.
     *
     * This used to be a findByUsername on every authenticated request: the access
     * token's subject is the username, but the app works in userId, so each call
     * translated one to the other against the users table. It is reached from
     * essentially every authenticated path (OrderService checkout/pay/list/get,
     * plus the cart and product paths), and UserActionLogService did the identical
     * translation a second time on the log thread — one request resolving the same
     * mapping from MySQL twice.
     *
     * The fallback exists for access tokens minted before the uid claim. They live
     * only as long as app.jwt.access-expiration-ms, so this path drains within
     * minutes of a deploy — but without it every in-flight session would break the
     * moment this shipped.
     */
    public Long resolveUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new UnauthorizedException("No authenticated user in the security context");
        }
        if (auth.getPrincipal() instanceof AuthenticatedUser user && user.userId() != null) {
            return user.userId();
        }

        String username = auth.getName();
        log.debug("[Auth] Token for {} carries no uid claim — falling back to a lookup", username);
        return userRepository.findByUsername(username)
                // A valid token whose subject no longer exists is a 401, not a 500.
                // This used to be a bare .orElseThrow() -> NoSuchElementException ->
                // the handler's catch-all -> 500, telling the client the server broke
                // when in fact their account was gone.
                .orElseThrow(() -> new UnauthorizedException(
                        "Token subject no longer exists: " + username))
                .getId();
    }
}
