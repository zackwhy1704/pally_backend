package com.pally.domain.consent;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaEntity;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.ConsentRequiredException;
import com.pally.shared.exception.GuardianRequiredException;
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
 * Unit tests for the under-13 AI data-transfer consent gate in ConsentGuard.
 *
 * <p>The gate applies ONLY to under-13 users (PDPC 2024). Users aged 13 and
 * over self-consent and must never be blocked. Invariants verified:
 * <ul>
 *   <li>Under-13, no consent record → throws AiConsentRequiredException("AI_DATA_TRANSFER").</li>
 *   <li>Under-13, record without AI_DATA_TRANSFER purpose → still throws.</li>
 *   <li>Under-13, consent granted → allowed.</li>
 *   <li>13+ user → always allowed, regardless of consent records.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AiConsentGateTest {

    @Mock UserRepository userRepo;
    @Mock ConsentRecordJpaRepository consentRecordRepo;
    @Mock OrgStaffJpaRepository staffRepo;
    @Mock com.pally.infrastructure.persistence.organization.OrganizationJpaRepository orgRepo;

    private static final String USER_ID = "user-test";
    // 2026 - 2015 = 11 → under 13
    private static final int UNDER_13_BIRTH_YEAR = 2015;
    // 2026 - 2010 = 16 → 13+
    private static final int OVER_13_BIRTH_YEAR = 2010;

    private ConsentGuard guard() {
        return new ConsentGuard(userRepo, consentRecordRepo, new UserAgeService(),
                org.mockito.Mockito.mock(com.pally.domain.consent.ConsentRepository.class),
                org.mockito.Mockito.mock(com.pally.domain.consent.ConsentService.class),
                staffRepo, orgRepo);
    }

    private User under13User() {
        User u = new User();
        u.setId(USER_ID);
        u.setBirthYear(UNDER_13_BIRTH_YEAR);
        return u;
    }

    private User over13User() {
        User u = new User();
        u.setId(USER_ID);
        u.setBirthYear(OVER_13_BIRTH_YEAR);
        return u;
    }

    private ConsentRecordJpaEntity consentRecord(String purposes) {
        ConsentRecordJpaEntity r = new ConsentRecordJpaEntity();
        r.setId("consent-1");
        r.setUserId(USER_ID);
        r.setPurposes(purposes);
        r.setCreatedAt(Instant.now());
        return r;
    }

    // ── Under-13: no consent → blocked ─────────────────────────────────────

    @Test
    void requireAiConsent_under13_noConsentRecord_throwsAiConsentRequired() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(under13User()));
        when(consentRecordRepo.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> guard().requireAiConsent(USER_ID))
                .isInstanceOf(AiConsentRequiredException.class)
                .satisfies(ex ->
                        assertThat(((ConsentRequiredException) ex).getReason())
                                .isEqualTo(ConsentGuard.REASON_AI_DATA_TRANSFER));
    }

    @Test
    void requireAiConsent_under13_recordWithoutAiPurpose_throws() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(under13User()));
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"tutoring\"]") // no AI_DATA_TRANSFER purpose
        ));

        assertThatThrownBy(() -> guard().requireAiConsent(USER_ID))
                .isInstanceOf(AiConsentRequiredException.class);
    }

    @Test
    void requireAiConsent_under13_recordForDifferentUser_throws() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(under13User()));
        ConsentRecordJpaEntity other = consentRecord("[\"AI_DATA_TRANSFER\"]");
        other.setUserId("someone-else");
        when(consentRecordRepo.findAll()).thenReturn(List.of(other));

        assertThatThrownBy(() -> guard().requireAiConsent(USER_ID))
                .isInstanceOf(AiConsentRequiredException.class);
    }

    // ── Under-13: consent granted → allowed ────────────────────────────────

    @Test
    void requireAiConsent_under13_consentGranted_allowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(under13User()));
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"AI_DATA_TRANSFER\",\"tutoring\"]")
        ));

        assertThatCode(() -> guard().requireAiConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    // ── 13+: gate is always a no-op ────────────────────────────────────────

    @Test
    void requireAiConsent_over13_noConsentRecord_allowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(over13User()));
        // No consent records at all — must still pass for 13+ users.

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

    // ── requireAiAllowed: combined gate that every AI endpoint calls ───────────

    @Test
    void requireAiAllowed_under13_noConsent_throwsAiConsentRequired() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(under13User()));
        when(consentRecordRepo.findAll()).thenReturn(List.of());

        assertThatThrownBy(() -> guard().requireAiAllowed(USER_ID))
                .isInstanceOf(AiConsentRequiredException.class);
    }

    // NOTE: requireAiAllowed is now purely the AI-transfer gate; the under-13 guardian
    // check moved to the single ingress guard (requireChildDataIngressConsent), so
    // requireAiAllowed no longer throws GuardianRequired — covered in GuardianGateTest.

    @Test
    void requireAiAllowed_under13_consentAndParentLinked_allowed() {
        User u = under13User();
        u.setParentId("parent-1");
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(u));
        when(consentRecordRepo.findAll()).thenReturn(List.of(
                consentRecord("[\"AI_DATA_TRANSFER\"]")
        ));

        assertThatCode(() -> guard().requireAiAllowed(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void requireAiAllowed_over13_alwaysAllowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(over13User()));

        assertThatCode(() -> guard().requireAiAllowed(USER_ID))
                .doesNotThrowAnyException();
    }
}
