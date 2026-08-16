package com.example.shophub.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtUtil {

    private final SecretKey key;
    private final long accessExpirationMs;
    private final long refreshExpirationMs;

    public JwtUtil(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-expiration-ms}") long accessExpirationMs,
            @Value("${app.jwt.refresh-expiration-ms}") long refreshExpirationMs) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessExpirationMs = accessExpirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
    }

    /**
     * Short-lived access token. Carries username + role. Lifetime comes from
     * app.jwt.access-expiration-ms — deliberately not restated here, because
     * that number is the revocation exposure window and a stale copy of it is
     * exactly what someone would quote wrongly while reasoning about an incident.
     */
    public String generateAccessToken(String username, String role, Long userId) {
        return Jwts.builder()
                .subject(username)
                // The app works in userId (Order.userId, Cart.userId, …) while the
                // subject is the username, so every authenticated request used to
                // translate one to the other against the users table. Carrying the
                // id here removes that query. Safe to cache in a token precisely
                // because a user's numeric id is immutable — unlike role, which is
                // why role has a staleness window and uid does not.
                .claim("uid", userId)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessExpirationMs))
                .signWith(key)
                .compact();
    }

    /**
     * Long-lived refresh token (app.jwt.refresh-expiration-ms). Carries a unique
     * jti so it can be individually revoked in Redis (refresh:{jti} → userId).
     */
    public String generateRefreshToken(String username) {
        return Jwts.builder()
                .subject(username)
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refreshExpirationMs))
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    /**
     * Returns null for access tokens minted before the uid claim existed, and for
     * refresh tokens, which never carry it.
     *
     * Read as Number rather than Long.class: JJWT deserializes a small JSON integer
     * as Integer, and get("uid", Long.class) throws on the type mismatch.
     */
    public Long extractUserId(String token) {
        Object uid = parseClaims(token).get("uid");
        return uid instanceof Number n ? n.longValue() : null;
    }

    public String extractJti(String token) {
        return parseClaims(token).getId();
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public long getRefreshExpirationMs() {
        return refreshExpirationMs;
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
