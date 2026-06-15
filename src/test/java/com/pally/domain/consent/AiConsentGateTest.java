package com.pally.domain.consent;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaEntity;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.ConsentRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the ALWAYS-ON AI data-transfer consent gate in ConsentGuard.
 *
 * <p>The legacy {@code app.ai-consent.enabled} flag is gone — the gate is now
 * unconditional. Invariants verified:
 * <ul>
 *   <li>No consent record → throws AiConsentRequiredException("AI_DATA_TRANSFER").</li>
 *   <li>A record without the AI_DATA_TRANSFER purpose → still throws.</li>
 *   <li>A record WITH the AI_DATA_TRANSFER purpose → allowed.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AiConsentGateTest {

    @Mock UserRepository userRepo;
    @Mock ConsentRecordJpaRepository consentRecordRepo;

    private static final String USER_ID = "user-test";

    private ConsentGuard guard() {
        return new ConsentGuard(userRepo, consentRecordRepo, new UserAgeService());
    }

    private ConsentRecordJpaEntity consentRecord(String purposes) {
        ConsentRecordJpaEntity r = new ConsentRecordJpaEntity();
        r.setId("consent-1");
        r.setUserId(USER_ID);
        r.setPurposes(purposes);
        r.setCreatedAt(Instant.now());
        return r;
    }

    // ── Always-on: no consent → blocked ────────────────────────────────────

    @Test
    void requireAiConsent_noConsentRecord_throwsAiConsentRequired() {
        when(consentRecordRepo.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> guard().requireAiConsent(USER_ID))
                .isInstanceOf(AiConsentRequiredException.class)
                .satisfies(ex ->
                        assertThat(((ConsentRequiredException) ex).getReason())
                                .isEqualTo(ConsentGuard.REASON_AI_DATA_TRANSFER));
    }

    @Test
    void requireAiConsent_recordWithoutAiPurpose_throws() {
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"tutoring\"]") // no AI_DATA_TRANSFER purpose
        ));

        assertThatThrownBy(() -> guard().requireAiConsent(USER_ID))
                .isInstanceOf(AiConsentRequiredException.class);
    }

    @Test
    void requireAiConsent_recordForDifferentUser_throws() {
        ConsentRecordJpaEntity other = consentRecord("[\"AI_DATA_TRANSFER\"]");
        other.setUserId("someone-else");
        when(consentRecordRepo.findAll()).thenReturn(List.of(other));

        assertThatThrownBy(() -> guard().requireAiConsent(USER_ID))
                .isInstanceOf(AiConsentRequiredException.class);
    }

    // ── Always-on: consent granted → allowed ───────────────────────────────

    @Test
    void requireAiConsent_consentGranted_allowed() {
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"AI_DATA_TRANSFER\",\"tutoring\"]")
        ));

        assertThatCode(() -> guard().requireAiConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    // ── requireActive is independent of the AI gate ────────────────────────

    @Test
    void requireActive_passesForActiveUser() {
        User user = new User();
        user.setId(USER_ID);
        user.setAccountStatus(ConsentGuard.STATUS_ACTIVE);
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));

        assertThatCode(() -> guard().requireActive(USER_ID, "UPLOAD"))
                .doesNotThrowAnyException();
    }
}
