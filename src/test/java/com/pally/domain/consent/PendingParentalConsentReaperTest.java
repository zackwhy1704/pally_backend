package com.pally.domain.consent;

import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Part B retention: the reaper deletes never-approved under-13 PENDING accounts past
 * the window, and must NEVER delete one whose parent did approve.
 */
@ExtendWith(MockitoExtension.class)
class PendingParentalConsentReaperTest {

    @Mock UserJpaRepository userRepo;
    @Mock ConsentRepository consentRepository;
    @Mock DeleteAccountUseCase deleteAccountUseCase;

    @InjectMocks PendingParentalConsentReaper reaper;

    private UserJpaEntity userEntity(String id) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        return u;
    }

    private ConsentRepository.ConsentRequest approved(String childId) {
        return new ConsentRepository.ConsentRequest(
                "r", childId, "p@test.com", "tok", "APPROVED",
                Instant.now(), Instant.now().plusSeconds(60), Instant.now());
    }

    @Test
    void reap_deletesUnapprovedPending_skipsApproved() {
        UserJpaEntity unapproved = userEntity("kid-unapproved");
        UserJpaEntity approvedKid = userEntity("kid-approved");
        when(userRepo.findByAccountStatusAndCreatedAtBefore(eq("PENDING_CONSENT"), any()))
                .thenReturn(List.of(unapproved, approvedKid));
        when(consentRepository.findLatestRequestByChildUserIdAndStatus("kid-unapproved", "APPROVED"))
                .thenReturn(Optional.empty());
        when(consentRepository.findLatestRequestByChildUserIdAndStatus("kid-approved", "APPROVED"))
                .thenReturn(Optional.of(approved("kid-approved")));

        reaper.reap();

        verify(deleteAccountUseCase).execute("kid-unapproved", null, null);
        verify(deleteAccountUseCase, never()).execute(eq("kid-approved"), any(), any());
    }
}
