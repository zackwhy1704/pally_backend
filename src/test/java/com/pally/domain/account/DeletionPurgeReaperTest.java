package com.pally.domain.account;

import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.email.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeletionPurgeReaper} — pins the LOCKED purge invariants:
 * empty-centre accounts are purged (completed email BEFORE the delete), an org
 * acquired during grace aborts loudly without cascading, and one user's failure
 * never stops the batch (that user just stays DELETION_PENDING).
 */
@ExtendWith(MockitoExtension.class)
class DeletionPurgeReaperTest {

    @Mock UserRepository userRepo;
    @Mock DeleteAccountUseCase deleteAccountUseCase;
    @Mock CentreAccessService centreAccess;
    @Mock EmailService emailService;

    DeletionPurgeReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new DeletionPurgeReaper(userRepo, deleteAccountUseCase, centreAccess, emailService);
        ReflectionTestUtils.setField(reaper, "graceDays", 14);
        ReflectionTestUtils.setField(reaper, "batchSize", 50);
        ReflectionTestUtils.setField(reaper, "retryBackoffHours", 20);
    }

    private User user(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    void reap_emptyCentreUser_purgesThenEmails_inThatOrder() {
        when(userRepo.findPurgeCandidates(any(), any(), eq(50)))
                .thenReturn(List.of(user("u1", "a@b.com")));
        when(centreAccess.isOwnedCentreEmpty("u1")).thenReturn(true);

        reaper.reap();

        // Email fires AFTER a confirmed purge, never before (defect: a failed purge must
        // not tell the user they were deleted).
        var ord = inOrder(deleteAccountUseCase, emailService);
        ord.verify(deleteAccountUseCase).execute("u1", null, null);
        ord.verify(emailService).sendHtml(eq("a@b.com"), anyString(), anyString());
    }

    @Test
    void reap_orgAcquiredDuringGrace_abortsLoud_noPurge_noEmail_marksAttempt() {
        when(userRepo.findPurgeCandidates(any(), any(), anyInt()))
                .thenReturn(List.of(user("owner", "o@b.com")));
        when(centreAccess.isOwnedCentreEmpty("owner")).thenReturn(false);

        reaper.reap();

        verify(deleteAccountUseCase, never()).execute(anyString(), any(), any());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
        // Anti-starvation: the aborted account is marked so the backoff excludes it next run.
        verify(userRepo).markDeletionAttempt(eq("owner"), any());
    }

    @Test
    void reap_oneUserFails_noEmailForFailed_othersStillPurged_andFailedMarked() {
        when(userRepo.findPurgeCandidates(any(), any(), anyInt()))
                .thenReturn(List.of(user("bad", "bad@b.com"), user("good", "good@b.com")));
        when(centreAccess.isOwnedCentreEmpty(anyString())).thenReturn(true);
        doThrow(new RuntimeException("boom"))
                .when(deleteAccountUseCase).execute("bad", null, null);

        reaper.reap();

        verify(deleteAccountUseCase).execute("good", null, null);      // isolation
        verify(emailService, never()).sendHtml(eq("bad@b.com"), anyString(), anyString()); // no lie
        verify(emailService).sendHtml(eq("good@b.com"), anyString(), anyString());
        verify(userRepo).markDeletionAttempt(eq("bad"), any());        // backoff on failure
    }

    @Test
    void reap_noPendingAccounts_isNoOp() {
        when(userRepo.findPurgeCandidates(any(), any(), anyInt())).thenReturn(List.of());

        reaper.reap();

        verifyNoInteractions(deleteAccountUseCase, centreAccess, emailService);
    }
}
