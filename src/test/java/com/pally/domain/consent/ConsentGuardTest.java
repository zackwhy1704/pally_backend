package com.pally.domain.consent;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * PDPA: an under-13's data may be ingested (uploaded / sent to Claude/Gemini) only
 * after VERIFIED parental CONSENT — the APPROVED consent record — NOT merely a
 * claimed parent link. canIngestChildData must gate on APPROVED, matching its javadoc.
 */
@ExtendWith(MockitoExtension.class)
class ConsentGuardTest {

    @Mock UserRepository userRepo;
    @Mock ConsentRecordJpaRepository consentRecordRepo;
    @Mock UserAgeService userAgeService;
    @Mock ConsentRepository consentRepository;
    @Mock ConsentService consentService;
    @Mock OrgStaffJpaRepository staffRepo;
    @Mock OrganizationJpaRepository orgRepo;

    ConsentGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ConsentGuard(userRepo, consentRecordRepo, userAgeService,
                consentRepository, consentService, staffRepo, orgRepo);
    }

    private void notCentreOperator() {
        lenient().when(staffRepo.existsByUserIdAndStatus(any(), any())).thenReturn(false);
        lenient().when(orgRepo.existsByOwnerUserId(any())).thenReturn(false);
    }

    private User under13(boolean linked) {
        User u = new User();
        u.setId("c1");
        u.setBirthYear(2016);
        if (linked) u.setParentId("p1");
        return u;
    }

    private ConsentRepository.ConsentRequest approvedRequest() {
        return new ConsentRepository.ConsentRequest("r1", "c1", "p@x.com", "tok",
                ConsentRepository.ConsentRequest.STATUS_APPROVED,
                Instant.now(), Instant.now().plusSeconds(3600), Instant.now());
    }

    @Test
    void under13_parentLinkedButNotApproved_isBLOCKED() {
        notCentreOperator();
        User u = under13(true); // link code claimed…
        when(userAgeService.isUnder13(u)).thenReturn(true);
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                eq("c1"), eq(ConsentRepository.ConsentRequest.STATUS_APPROVED)))
                .thenReturn(Optional.empty()); // …but NOT approved

        assertThat(guard.canIngestChildData(u))
                .as("a claimed parent link is not consent — must be blocked")
                .isFalse();
    }

    @Test
    void under13_approved_isAllowed() {
        notCentreOperator();
        User u = under13(true);
        when(userAgeService.isUnder13(u)).thenReturn(true);
        when(consentRepository.findLatestRequestByChildUserIdAndStatus(
                eq("c1"), eq(ConsentRepository.ConsentRequest.STATUS_APPROVED)))
                .thenReturn(Optional.of(approvedRequest()));

        assertThat(guard.canIngestChildData(u)).isTrue();
    }

    @Test
    void thirteenPlus_isAllowed_selfConsent() {
        notCentreOperator();
        User u = new User();
        u.setId("t1");
        u.setBirthYear(2008);
        when(userAgeService.isUnder13(u)).thenReturn(false);

        assertThat(guard.canIngestChildData(u)).isTrue();
    }

    @Test
    void unknownAge_isBlocked() {
        notCentreOperator();
        User u = new User();
        u.setId("x1"); // birthYear null

        assertThat(guard.canIngestChildData(u)).isFalse();
    }
}
