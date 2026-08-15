package com.example.shophub.service;

import com.example.shophub.dto.AuthResponse;
import com.example.shophub.dto.RegisterRequest;
import com.example.shophub.entity.Cart;
import com.example.shophub.entity.User;
import com.example.shophub.enums.Role;
import com.example.shophub.exception.DuplicateUsernameException;
import com.example.shophub.repository.jpa.CartRepository;
import com.example.shophub.repository.jpa.UserRepository;
import com.example.shophub.security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    @Mock UserRepository        userRepository;
    @Mock CartRepository        cartRepository;
    @Mock PasswordEncoder       passwordEncoder;
    @Mock JwtUtil               jwtUtil;
    @Mock AuthenticationManager authenticationManager;
    @Mock RefreshTokenService   refreshTokenService;
    @InjectMocks AuthService    authService;

    private RegisterRequest request(String username) {
        RegisterRequest r = new RegisterRequest();
        r.setUsername(username);
        r.setPassword("hunter2");
        return r;
    }

    /**
     * F4. A taken username is a well-formed request conflicting with existing
     * state — 409. It used to throw a bare RuntimeException, which the handler's
     * catch-all mapped to 500, telling the client the server broke.
     */
    @Test
    void register_duplicateUsername_throwsDuplicateUsernameException() {
        when(userRepository.existsByUsername("avery")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request("avery")))
                .isInstanceOf(DuplicateUsernameException.class)
                .hasMessageContaining("avery");

        verify(userRepository, never()).save(any(User.class));
        verify(cartRepository, never()).save(any(Cart.class));
    }

    @Test
    void register_newUsername_createsUserAndCartAndReturnsTokens() {
        when(userRepository.existsByUsername("newbie")).thenReturn(false);
        when(passwordEncoder.encode("hunter2")).thenReturn("$2a$hashed");
        doAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            u.setRole(Role.USER);
            return u;
        }).when(userRepository).save(any(User.class));
        when(jwtUtil.generateAccessToken(anyString(), anyString(), anyLong())).thenReturn("access-token");
        when(jwtUtil.generateRefreshToken(anyString())).thenReturn("refresh-token");
        when(jwtUtil.extractJti("refresh-token")).thenReturn("jti-1");
        when(jwtUtil.getRefreshExpirationMs()).thenReturn(86400000L);

        AuthResponse response = authService.register(request("newbie"));

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");
        verify(cartRepository).save(any(Cart.class));
        verify(refreshTokenService).store("jti-1", 7L, 86400000L);
    }
}
