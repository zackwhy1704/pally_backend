package com.pally.domain.consent;

import com.pally.infrastructure.persistence.consent.ConsentRecordJpaEntity;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.ConsentRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AI data-transfer consent gate in ConsentGuard.
 *
 * <p>Invariants verified:
 * - Flag OFF → upload proceeds without consent check (no exception thrown)
 * - Flag OFF → chat proceeds without consent check
 * - Flag ON + no consent → throws ConsentRequiredException("AI_DATA_TRANSFER")
 * - Flag ON + consent granted → no exception
 */
@ExtendWith(MockitoExtension.class)
class AiConsentGateTest {

    @Mock UserJpaRepository userRepo;
    @Mock ConsentRecordJpaRepository consentRecordRepo;

    private static final String USER_ID = "user-test";

    private ConsentGuard guardWithFlagOff() {
        ConsentGuard guard = new ConsentGuard(userRepo, consentRecordRepo);
        ReflectionTestUtils.setField(guard, "aiConsentEnabled", false);
        return guard;
    }

    private ConsentGuard guardWithFlagOn() {
        ConsentGuard guard = new ConsentGuard(userRepo, consentRecordRepo);
        ReflectionTestUtils.setField(guard, "aiConsentEnabled", true);
        return guard;
    }

    private ConsentRecordJpaEntity consentRecord(String purposes) {
        ConsentRecordJpaEntity r = new ConsentRecordJpaEntity();
        r.setId("consent-1");
        r.setUserId(USER_ID);
        r.setPurposes(purposes);
        r.setCreatedAt(Instant.now());
        return r;
    }

    // ── Flag OFF tests ─────────────────────────────────────────────────────

    @Test
    void requireAiConsent_flagOff_uploadProceedsWithoutCheck() {
        ConsentGuard guard = guardWithFlagOff();
        // No stubs needed — consentRecordRepo should never be called

        assertThatCode(() -> guard.requireAiConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAiConsent_flagOff_chatProceedsWithoutCheck() {
        ConsentGuard guard = guardWithFlagOff();

        // Simulate chat use-case calling requireAiConsent
        assertThatCode(() -> guard.requireAiConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    // ── Flag ON, no consent ────────────────────────────────────────────────

    @Test
    void requireAiConsent_flagOn_noConsent_uploadThrows() {
        ConsentGuard guard = guardWithFlagOn();
        when(consentRecordRepo.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> guard.requireAiConsent(USER_ID))
                .isInstanceOf(ConsentRequiredException.class)
                .satisfies(ex ->
                        assertThat(((ConsentRequiredException) ex).getReason())
                                .isEqualTo(ConsentGuard.REASON_AI_DATA_TRANSFER));
    }

    @Test
    void requireAiConsent_flagOn_noConsent_chatThrows() {
        ConsentGuard guard = guardWithFlagOn();
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"tutoring\"]") // has tutoring but NOT AI_DATA_TRANSFER
        ));

        assertThatThrownBy(() -> guard.requireAiConsent(USER_ID))
                .isInstanceOf(ConsentRequiredException.class);
    }

    // ── Flag ON, consent granted ───────────────────────────────────────────

    @Test
    void requireAiConsent_flagOn_consentGranted_uploadAllowed() {
        ConsentGuard guard = guardWithFlagOn();
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"AI_DATA_TRANSFER\",\"tutoring\"]")
        ));

        assertThatCode(() -> guard.requireAiConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAiConsent_flagOn_consentGranted_chatAllowed() {
        ConsentGuard guard = guardWithFlagOn();
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"AI_DATA_TRANSFER\",\"quiz\"]")
        ));

        assertThatCode(() -> guard.requireAiConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    // ── requireActive is unaffected by the AI consent flag ────────────────

    @Test
    void requireActive_alwaysPassesForActiveUser_regardlessOfAiFlag() {
        // Test that the original requireActive method still works correctly
        // with the new constructor signature.
        ConsentGuard guard = guardWithFlagOn();
        UserJpaEntity user = new UserJpaEntity();
        user.setId(USER_ID);
        user.setAccountStatus(ConsentGuard.STATUS_ACTIVE);
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatCode(() -> guard.requireActive(USER_ID, "UPLOAD"))
                .doesNotThrowAnyException();
    }
}
