package com.pally.infrastructure.auth;

import com.pally.domain.consent.ConsentService;
import com.pally.domain.subscription.PremiumService;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the DEFERRED.md-closed gap: AuthController.register (memoly web's /signup form)
 * created accounts with NO Terms-of-Use gate at all — this is the DEFENSE-IN-DEPTH
 * check at the service layer, independent of RegisterRequest's DTO-level @AssertTrue
 * (which AuthIntegrationTest.register_termsNotAccepted_rejects400_noAccountCreated
 * pins through the real HTTP/@Valid pipeline). Both layers are proven separately —
 * the DTO layer alone wouldn't catch a future caller that bypasses @Valid.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceRegisterTest {

    @Mock UserJpaRepository userRepo;
    @Mock JwtService jwtService;
    @Mock com.pally.domain.shop.CharacterShopService characterShopService;
    @Mock PremiumService premiumService;
    @Mock ConsentService consentService;
    @Mock BCryptPasswordEncoder passwordEncoder;
    @InjectMocks AuthService authService;

    @BeforeEach
    void defaults() {
        when(userRepo.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$2a$hashed");
        when(jwtService.generateToken(anyString(), any(), anyInt())).thenReturn("tok");
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void termsNotAccepted_rejects400_noAccountCreated() {
        assertThatThrownBy(() -> authService.register(
                "adult@test.com", "password123", "Centre Admin", "adult", null, null, false))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Terms of Use");

        verify(userRepo, never()).save(any());
        verify(consentService, never()).recordTermsAcceptance(any());
        verify(jwtService, never()).generateToken(anyString(), any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void termsAccepted_createsAccount_recordsConsentAfterAccountCreated() {
        var result = authService.register(
                "adult2@test.com", "password123", "Centre Admin", "adult", null, null, true);

        assertThat(result.userId()).isNotBlank();
        verify(userRepo).save(any());
        verify(consentService).recordTermsAcceptance(result.userId());
        verify(premiumService).grantTrial(result.userId());
    }
}
