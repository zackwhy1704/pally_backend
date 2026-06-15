package com.pally.api.auth;

import com.pally.domain.referral.ReferralService;
import com.pally.infrastructure.persistence.auth.EmailVerificationTokenJpaEntity;
import com.pally.infrastructure.persistence.auth.EmailVerificationTokenJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EmailVerificationController} — token issue,
 * verification happy-path, and all error branches.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationControllerTest {

    @Mock EmailVerificationTokenJpaRepository tokenRepo;
    @Mock UserJpaRepository userRepo;
    @Mock ReferralService referralService;

    @InjectMocks EmailVerificationController controller;

    private static final String USER_ID = "user-1";

    private UserJpaEntity unverifiedUser() {
        var u = new UserJpaEntity();
        u.setEmail("test@example.com");
        u.setEmailVerified(false);
        return u;
    }

    private UserJpaEntity verifiedUser() {
        var u = new UserJpaEntity();
        u.setEmail("test@example.com");
        u.setEmailVerified(true);
        return u;
    }

    @BeforeEach
    void setup() {
        when(tokenRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── sendVerification ────────────────────────────────────────────────────

    @Test
    void sendVerification_unknownUser_throws404() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.sendVerification(USER_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void sendVerification_alreadyVerified_returnsAlreadyVerifiedTrue() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(verifiedUser()));

        var res = controller.sendVerification(USER_ID);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().data().get("alreadyVerified")).isEqualTo(true);
        verify(tokenRepo, never()).save(any());
    }

    @Test
    void sendVerification_unverifiedUser_savesTokenAndReturnsSentTrue() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(unverifiedUser()));

        var res = controller.sendVerification(USER_ID);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().data().get("sent")).isEqualTo(true);
        assertThat(res.getBody().data()).containsKey("expiresAt");
        verify(tokenRepo).save(any(EmailVerificationTokenJpaEntity.class));
    }

    // ── verifyEmail ─────────────────────────────────────────────────────────

    @Test
    void verifyEmail_missingToken_throws400() {
        assertThatThrownBy(() -> controller.verifyEmail(Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void verifyEmail_blankToken_throws400() {
        assertThatThrownBy(() -> controller.verifyEmail(Map.of("token", "  ")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void verifyEmail_unknownToken_throws404() {
        when(tokenRepo.findById("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.verifyEmail(Map.of("token", "bad-token")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid");
    }

    @Test
    void verifyEmail_alreadyUsedToken_throws410() {
        var row = new EmailVerificationTokenJpaEntity();
        row.setToken("tok");
        row.setUserId(USER_ID);
        row.setExpiresAt(Instant.now().plusSeconds(3600));
        row.setUsedAt(Instant.now().minusSeconds(10));
        when(tokenRepo.findById("tok")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> controller.verifyEmail(Map.of("token", "tok")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    void verifyEmail_expiredToken_throws410() {
        var row = new EmailVerificationTokenJpaEntity();
        row.setToken("tok");
        row.setUserId(USER_ID);
        row.setExpiresAt(Instant.now().minusSeconds(1));
        when(tokenRepo.findById("tok")).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> controller.verifyEmail(Map.of("token", "tok")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void verifyEmail_validToken_setsEmailVerifiedAndReturnsTrue() {
        var row = new EmailVerificationTokenJpaEntity();
        row.setToken("tok");
        row.setUserId(USER_ID);
        row.setExpiresAt(Instant.now().plusSeconds(3600));

        var user = unverifiedUser();
        when(tokenRepo.findById("tok")).thenReturn(Optional.of(row));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));

        var res = controller.verifyEmail(Map.of("token", "tok"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().data().get("verified")).isEqualTo(true);
        assertThat(user.isEmailVerified()).isTrue();
        assertThat(row.getUsedAt()).isNotNull();
    }

    @Test
    void verifyEmail_referralServiceThrows_doesNotBubbleUp() {
        var row = new EmailVerificationTokenJpaEntity();
        row.setToken("tok");
        row.setUserId(USER_ID);
        row.setExpiresAt(Instant.now().plusSeconds(3600));

        when(tokenRepo.findById("tok")).thenReturn(Optional.of(row));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(unverifiedUser()));
        org.mockito.Mockito.doThrow(new RuntimeException("referral svc down"))
                .when(referralService).onFirstQuizAnswer(USER_ID);

        // Referral failure must not prevent verification succeeding
        var res = controller.verifyEmail(Map.of("token", "tok"));
        assertThat(res.getBody().data().get("verified")).isEqualTo(true);
    }
}
