package com.pally.domain.consent;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.shared.exception.GuardianRequiredException;
import com.pally.shared.exception.ParentalConsentPendingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Year;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The ONE child-data ingress guard (default-deny):
 * {@link ConsentGuard#requireChildDataIngressConsent(String)} +
 * {@link ConsentGuard#canIngestChildData(User)}.
 *
 * <p>Invariants:
 * <ul>
 *   <li>Unknown age (null birth year) → DENIED with AGE_DECLARATION_REQUIRED (default-deny;
 *       a child can't bypass by omitting age).</li>
 *   <li>Under-13, no consent → DENIED with PARENTAL_CONSENT_PENDING (masked email + resend).</li>
 *   <li>Under-13 + approved parental consent → allowed.</li>
 *   <li>Under-13 + linked parent → allowed.</li>
 *   <li>Established 13+ → allowed.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GuardianGateTest {

    @Mock UserRepository userRepo;
    @Mock ConsentRecordJpaRepository consentRecordRepo;
    @Mock ConsentRepository consentRepo;
    @Mock ConsentService consentService;
    @Mock OrgStaffJpaRepository staffRepo;

    private static final String USER_ID = "kid-1";
    private static final String STAFF_ID = "staff-1";

    private ConsentGuard guard() {
        return new ConsentGuard(userRepo, consentRecordRepo, new UserAgeService(), consentRepo, consentService, staffRepo);
    }

    private ConsentRepository.ConsentRequest approvedRequest() {
        return new ConsentRepository.ConsentRequest(
                "req-1", USER_ID, "parent@test.com", "tok", "APPROVED",
                java.time.Instant.now(), java.time.Instant.now().plusSeconds(600),
                java.time.Instant.now());
    }

    private int sgYear() {
        return Year.now(ZoneId.of("Asia/Singapore")).getValue();
    }

    private User user(Integer birthYear, String parentId) {
        User u = new User();
        u.setId(USER_ID);
        u.setBirthYear(birthYear);
        u.setParentId(parentId);
        return u;
    }

    @Test
    void unknownAge_isDeniedWithAgeDeclaration_notAllowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(null, null)));

        assertThatThrownBy(() -> guard().requireChildDataIngressConsent(USER_ID))
                .isInstanceOf(GuardianRequiredException.class)
                .satisfies(e -> assertThat(((GuardianRequiredException) e).getReason())
                        .isEqualTo(ConsentGuard.REASON_AGE_DECLARATION_REQUIRED));
    }

    @Test
    void under13_noConsent_isDeniedWithParentalConsentPending_carryingMaskedEmailAndResend() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 10, null)));
        when(consentRepo.findLatestRequestByChildUserIdAndStatus(USER_ID, "APPROVED"))
                .thenReturn(Optional.empty());
        when(consentService.resendInfo(USER_ID))
                .thenReturn(new ConsentService.ResendInfo("j***@gmail.com", false, 42));

        assertThatThrownBy(() -> guard().requireChildDataIngressConsent(USER_ID))
                .isInstanceOf(ParentalConsentPendingException.class)
                .satisfies(e -> {
                    ParentalConsentPendingException ex = (ParentalConsentPendingException) e;
                    assertThat(ex.getMaskedParentEmail()).isEqualTo("j***@gmail.com");
                    assertThat(ex.isResendAvailable()).isFalse();
                    assertThat(ex.getResendAvailableInSeconds()).isEqualTo(42);
                });
    }

    @Test
    void under13_withApprovedParentalConsent_isAllowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 10, null)));
        when(consentRepo.findLatestRequestByChildUserIdAndStatus(USER_ID, "APPROVED"))
                .thenReturn(Optional.of(approvedRequest()));

        assertThatCode(() -> guard().requireChildDataIngressConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void under13_withLinkedParent_isAllowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 10, "parent-1")));
        lenient().when(consentRepo.findLatestRequestByChildUserIdAndStatus(USER_ID, "APPROVED"))
                .thenReturn(Optional.empty());

        assertThatCode(() -> guard().requireChildDataIngressConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void establishedThirteenPlus_isAllowed() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(sgYear() - 16, null)));

        assertThatCode(() -> guard().requireChildDataIngressConsent(USER_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void canIngestChildData_unknownAge_isFalse_defaultDeny() {
        assertThat(guard().canIngestChildData(user(null, null))).isFalse();
    }

    // ── Staff bypass — active centre teachers must never hit the child-data gate ──

    @Test
    void requireChildDataIngressConsent_activeStaff_noBirthYear_passes() {
        // A centre teacher registered without a birth year must NOT be blocked.
        // This is the exact condition that triggered "A grown-up needs to approve your account".
        User staffUser = user(null, null);
        staffUser.setId(STAFF_ID);
        when(userRepo.findById(STAFF_ID)).thenReturn(Optional.of(staffUser));
        when(staffRepo.existsByUserIdAndStatus(STAFF_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(true);

        assertThatCode(() -> guard().requireChildDataIngressConsent(STAFF_ID))
                .doesNotThrowAnyException();
    }

    @Test
    void requireChildDataIngressConsent_nonStaff_noBirthYear_stillBlocked() {
        // Regression guard: a non-staff user with no birth year must still be denied.
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user(null, null)));
        when(staffRepo.existsByUserIdAndStatus(USER_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(false);

        assertThatThrownBy(() -> guard().requireChildDataIngressConsent(USER_ID))
                .isInstanceOf(GuardianRequiredException.class)
                .satisfies(e -> assertThat(((GuardianRequiredException) e).getReason())
                        .isEqualTo(ConsentGuard.REASON_AGE_DECLARATION_REQUIRED));
    }

    @Test
    void canIngestChildData_activeStaff_noBirthYear_returnsTrue() {
        User staffUser = user(null, null);
        staffUser.setId(STAFF_ID);
        when(staffRepo.existsByUserIdAndStatus(STAFF_ID, OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(true);

        assertThat(guard().canIngestChildData(staffUser)).isTrue();
    }
}
