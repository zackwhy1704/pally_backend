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
    }

    private User user(String id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    void reap_emptyCentreUser_emailsThenPurges_inThatOrder() {
        when(userRepo.findDeletionPendingBefore(any(), eq(50)))
                .thenReturn(List.of(user("u1", "a@b.com")));
        when(centreAccess.isOwnedCentreEmpty("u1")).thenReturn(true);

        reaper.reap();

        var ord = inOrder(emailService, deleteAccountUseCase);
        ord.verify(emailService).sendHtml(eq("a@b.com"), anyString(), anyString());
        ord.verify(deleteAccountUseCase).execute("u1", null, null);
    }

    @Test
    void reap_orgAcquiredDuringGrace_abortsLoud_noPurge_noEmail() {
        when(userRepo.findDeletionPendingBefore(any(), anyInt()))
                .thenReturn(List.of(user("owner", "o@b.com")));
        when(centreAccess.isOwnedCentreEmpty("owner")).thenReturn(false);

        reaper.reap();

        verify(deleteAccountUseCase, never()).execute(anyString(), any(), any());
        verifyNoInteractions(emailService);
    }

    @Test
    void reap_oneUserFails_othersStillPurged() {
        when(userRepo.findDeletionPendingBefore(any(), anyInt()))
                .thenReturn(List.of(user("bad", null), user("good", null)));
        when(centreAccess.isOwnedCentreEmpty(anyString())).thenReturn(true);
        doThrow(new RuntimeException("boom"))
                .when(deleteAccountUseCase).execute("bad", null, null);

        reaper.reap();

        verify(deleteAccountUseCase).execute("good", null, null);
    }

    @Test
    void reap_noPendingAccounts_isNoOp() {
        when(userRepo.findDeletionPendingBefore(any(), anyInt())).thenReturn(List.of());

        reaper.reap();

        verifyNoInteractions(deleteAccountUseCase, centreAccess, emailService);
    }
}
