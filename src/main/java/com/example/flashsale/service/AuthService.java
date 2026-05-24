package com.example.flashsale.service;

import com.example.flashsale.dto.AuthResponse;
import com.example.flashsale.dto.LoginRequest;
import com.example.flashsale.dto.RefreshRequest;
import com.example.flashsale.dto.RegisterRequest;
import com.example.flashsale.entity.Cart;
import com.example.flashsale.entity.User;
import com.example.flashsale.repository.jpa.CartRepository;
import com.example.flashsale.repository.jpa.UserRepository;
import com.example.flashsale.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final CartRepository cartRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final RefreshTokenService refreshTokenService;

    /**
     * Creates user + cart in a single transaction (every user always has a cart row).
     * Returns access + refresh token pair.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new RuntimeException("Username already taken");
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        userRepository.save(user);

        // Cart created in same tx — every authenticated user always has a cart row
        Cart cart = new Cart();
        cart.setUserId(user.getId());
        cartRepository.save(cart);

        return issueTokenPair(user);
    }

    /** Authenticates credentials, issues a fresh token pair. */
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getUsername(),
                        request.getPassword()
                )
        );
        User user = userRepository.findByUsername(request.getUsername()).orElseThrow();
        return issueTokenPair(user);
    }

    /**
     * Verifies signature + Redis presence, then rotates:
     * deletes old refresh:{jti}, issues a brand-new pair.
     */
    public AuthResponse refresh(RefreshRequest request) {
        String token = request.getRefreshToken();

        if (!jwtUtil.isTokenValid(token)) {
            throw new RuntimeException("Invalid refresh token");
        }

        String jti = jwtUtil.extractJti(token);
        Long userId = refreshTokenService.lookup(jti);
        if (userId == null) {
            throw new RuntimeException("Refresh token revoked or expired");
        }

        refreshTokenService.revoke(jti);   // rotate out the old token
        User user = userRepository.findById(userId).orElseThrow();
        return issueTokenPair(user);
    }

    /**
     * Revokes the refresh token from Redis.
     * The 15m access token expires on its own — no blacklist needed.
     */
    public void logout(RefreshRequest request) {
        String token = request.getRefreshToken();
        if (jwtUtil.isTokenValid(token)) {
            refreshTokenService.revoke(jwtUtil.extractJti(token));
        }
    }

    // --- private helpers ---

    private AuthResponse issueTokenPair(User user) {
        String accessToken = jwtUtil.generateAccessToken(user.getUsername(), user.getRole().name());
        String refreshToken = jwtUtil.generateRefreshToken(user.getUsername());
        String jti = jwtUtil.extractJti(refreshToken);
        refreshTokenService.store(jti, user.getId(), jwtUtil.getRefreshExpirationMs());
        return new AuthResponse(accessToken, refreshToken);
    }
}
