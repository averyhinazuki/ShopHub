package com.example.shophub.security;

import com.example.shophub.entity.User;
import com.example.shophub.exception.UnauthorizedException;
import com.example.shophub.repository.jpa.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F7. resolveUserId ran a findByUsername on every authenticated request, and
 * UserActionLogService ran the identical query again on the log thread. The uid
 * claim removes both.
 */
@ExtendWith(MockitoExtension.class)
class SecurityUtilsTest {

    @Mock UserRepository userRepository;
    @InjectMocks SecurityUtils securityUtils;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(Object principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @Test
    void resolveUserId_withUidClaim_doesNotTouchTheDatabase() {
        authenticate(new AuthenticatedUser(42L, "avery", "USER"));

        assertThat(securityUtils.resolveUserId()).isEqualTo(42L);

        verify(userRepository, never()).findByUsername(any());
    }

    /** Tokens minted before the uid claim must keep working until they expire. */
    @Test
    void resolveUserId_legacyTokenWithoutUid_fallsBackToTheRepository() {
        authenticate(new AuthenticatedUser(null, "avery", "USER"));
        User user = new User();
        user.setId(42L);
        when(userRepository.findByUsername("avery")).thenReturn(Optional.of(user));

        assertThat(securityUtils.resolveUserId()).isEqualTo(42L);

        verify(userRepository).findByUsername("avery");
    }

    /**
     * A valid token whose subject no longer exists is a 401. It used to be a bare
     * .orElseThrow() -> NoSuchElementException -> the handler's catch-all -> 500,
     * telling the client the server broke when their account was simply gone.
     */
    @Test
    void resolveUserId_deletedUser_throwsUnauthorizedNotNoSuchElement() {
        authenticate(new AuthenticatedUser(null, "ghost", "USER"));
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> securityUtils.resolveUserId())
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("ghost");
    }

    /** AuthenticatedUser must keep Authentication.getName() returning the username. */
    @Test
    void authenticatedUser_isRecognisedAsAPrincipalSoGetNameStaysTheUsername() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(42L, "avery", "USER"), null, List.of());

        assertThat(auth.getName()).isEqualTo("avery");
    }
}
