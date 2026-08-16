package com.example.shophub.security;

import java.security.Principal;

/**
 * The principal placed in the SecurityContext by {@link JwtFilter}.
 *
 * Implements {@link Principal} deliberately: {@code Authentication.getName()}
 * checks for it and returns {@link #getName()}, so every existing caller that
 * reads {@code getName()} keeps seeing the username unchanged. Without that this
 * would be {@code toString()} and quietly break the audit log and the order
 * ownership checks.
 *
 * {@code userId} is null for access tokens minted before the {@code uid} claim
 * existed. Those expire within the access-token lifetime, so the null case drains
 * on its own shortly after a deploy — callers must tolerate it until then.
 */
public record AuthenticatedUser(Long userId, String username, String role) implements Principal {

    @Override
    public String getName() {
        return username;
    }
}
