package com.example.shophub.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil(
            "test-only-secret-key-that-is-at-least-256-bits-long-for-hmac",
            300_000L,
            86_400_000L);

    @Test
    void accessToken_carriesUsernameRoleAndUid() {
        String token = jwtUtil.generateAccessToken("avery", "USER", 42L);

        assertThat(jwtUtil.extractUsername(token)).isEqualTo("avery");
        assertThat(jwtUtil.extractRole(token)).isEqualTo("USER");
        assertThat(jwtUtil.extractUserId(token)).isEqualTo(42L);
    }

    /**
     * JJWT deserializes a small JSON integer as Integer, so reading the claim with
     * get("uid", Long.class) throws on the type mismatch. This pins the Number-based
     * read that avoids it.
     */
    @Test
    void extractUserId_handlesSmallIdsWithoutAClassCastFailure() {
        String token = jwtUtil.generateAccessToken("avery", "USER", 1L);

        assertThat(jwtUtil.extractUserId(token)).isEqualTo(1L);
    }

    /** Refresh tokens never carry uid, and neither did any pre-existing access token. */
    @Test
    void extractUserId_returnsNullWhenTheClaimIsAbsent() {
        String refreshToken = jwtUtil.generateRefreshToken("avery");

        assertThat(jwtUtil.extractUserId(refreshToken)).isNull();
    }
}
