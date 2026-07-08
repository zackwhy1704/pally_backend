package com.pally.infrastructure.auth;

import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.consent.ConsentService;
import com.pally.domain.consent.UserAgeService;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * completeProfile is the single "collect the missing age" path (social PENDING_PROFILE +
 * legacy null-birthYear). 13+ → ACTIVE (+ trial on first completion); under-13 → routed
 * into the parental-consent flow; missing parent email for an under-13 → rejected.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceProfileTest {

    @Mock UserJpaRepository userRepo;
    @Mock JwtService jwtService;
    @Mock UserAgeService userAgeService;
    @Mock PremiumService premiumService;
    @Mock ConsentService consentService;
    @InjectMocks AuthService authService;

    private UserJpaEntity pendingProfile() {
        UserJpaEntity u = new UserJpaEntity();
        u.setId("u1");
        u.setAccountStatus(ConsentGuard.STATUS_PENDING_PROFILE);
        return u;
    }

    @Test
    void thirteenPlus_becomesActive_andGetsTrialOnFirstCompletion() {
        when(userRepo.findById("u1")).thenReturn(Optional.of(pendingProfile()));
        when(userAgeService.isUnder13(2000)).thenReturn(false);
        when(jwtService.generateToken(anyString(), any(), anyInt())).thenReturn("tok");

        authService.completeProfile("u1", 2000, null);

        assertThat(pendingProfileStatusAfterSave()).isEqualTo(ConsentGuard.STATUS_ACTIVE);
        verify(premiumService).grantTrial("u1");
        verify(consentService, never()).requestParentConsent(anyString(), anyString());
    }

    @Test
    void under13WithParent_becomesPendingConsent_andRequestsConsent() {
        when(userRepo.findById("u1")).thenReturn(Optional.of(pendingProfile()));
        when(userAgeService.isUnder13(2018)).thenReturn(true);
        when(jwtService.generateToken(anyString(), any(), anyInt())).thenReturn("tok");

        authService.completeProfile("u1", 2018, "parent@test.com");

        verify(consentService).requestParentConsent("u1", "parent@test.com");
        verify(premiumService, never()).grantTrial(anyString());
    }

    @Test
    void under13WithoutParent_isRejected() {
        when(userRepo.findById("u1")).thenReturn(Optional.of(pendingProfile()));
        when(userAgeService.isUnder13(2018)).thenReturn(true);

        assertThatThrownBy(() -> authService.completeProfile("u1", 2018, null))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getHttpStatus()).isEqualTo(400);
        verify(userRepo, never()).save(any());
    }

    // captures the status set on the saved entity
    private String pendingProfileStatusAfterSave() {
        var captor = org.mockito.ArgumentCaptor.forClass(UserJpaEntity.class);
        verify(userRepo).save(captor.capture());
        return captor.getValue().getAccountStatus();
    }
}
