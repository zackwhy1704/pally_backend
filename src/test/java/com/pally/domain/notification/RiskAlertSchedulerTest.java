package com.pally.domain.notification;

import com.pally.domain.account.AccountType;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RiskAlertSchedulerTest {

    @Mock private UserRepository userRepo;
    @Mock private ActivityLogJpaRepository activityRepo;
    @Mock private MilestoneNotifier milestoneNotifier;

    private RiskAlertScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new RiskAlertScheduler(userRepo, activityRepo, milestoneNotifier);
    }

    @Test
    void checkInactiveChildren_noChildren_sendsNothing() {
        when(userRepo.findByAccountType(AccountType.CHILD)).thenReturn(List.of());

        scheduler.checkInactiveChildren();

        verifyNoInteractions(milestoneNotifier);
    }

    @Test
    void checkInactiveChildren_activeChild_noAlert() {
        User child = childWithParent("c1", "p1", "Alice");
        when(userRepo.findByAccountType(AccountType.CHILD)).thenReturn(List.of(child));
        when(activityRepo.countSince(eq("c1"), any())).thenReturn(5);

        scheduler.checkInactiveChildren();

        verifyNoInteractions(milestoneNotifier);
    }

    @Test
    void checkInactiveChildren_inactiveChildWithParent_sendsAlert() {
        User child = childWithParent("c1", "p1", "Bob");
        when(userRepo.findByAccountType(AccountType.CHILD)).thenReturn(List.of(child));
        when(activityRepo.countSince(eq("c1"), any())).thenReturn(0);

        scheduler.checkInactiveChildren();

        verify(milestoneNotifier).onInactiveChild("c1", "Bob");
    }

    @Test
    void checkInactiveChildren_inactiveChildWithoutParent_skipped() {
        User child = childWithoutParent("c1");
        when(userRepo.findByAccountType(AccountType.CHILD)).thenReturn(List.of(child));

        scheduler.checkInactiveChildren();

        verifyNoInteractions(milestoneNotifier);
        // Also verifies activityRepo is never consulted for parentless children
        verifyNoInteractions(activityRepo);
    }

    @Test
    void checkInactiveChildren_nullActivityCount_treatedAsZero() {
        User child = childWithParent("c1", "p1", "Charlie");
        when(userRepo.findByAccountType(AccountType.CHILD)).thenReturn(List.of(child));
        when(activityRepo.countSince(eq("c1"), any())).thenReturn(null);

        scheduler.checkInactiveChildren();

        verify(milestoneNotifier).onInactiveChild("c1", "Charlie");
    }

    @Test
    void checkInactiveChildren_mixedActiveAndInactive_onlyAlertsInactive() {
        User active = childWithParent("c1", "p1", "Alice");
        User inactive = childWithParent("c2", "p1", "Bob");
        when(userRepo.findByAccountType(AccountType.CHILD)).thenReturn(List.of(active, inactive));
        when(activityRepo.countSince(eq("c1"), any())).thenReturn(3);
        when(activityRepo.countSince(eq("c2"), any())).thenReturn(0);

        scheduler.checkInactiveChildren();

        verify(milestoneNotifier, never()).onInactiveChild(eq("c1"), anyString());
        verify(milestoneNotifier).onInactiveChild("c2", "Bob");
    }

    @Test
    void checkInactiveChildren_usesChildNameFallingBackToDisplayName() {
        User child = new User();
        child.setId("c1");
        child.setParentId("p1");
        child.setChildName(null);
        child.setDisplayName("FallbackName");
        when(userRepo.findByAccountType(AccountType.CHILD)).thenReturn(List.of(child));
        when(activityRepo.countSince(eq("c1"), any())).thenReturn(0);

        scheduler.checkInactiveChildren();

        verify(milestoneNotifier).onInactiveChild("c1", "FallbackName");
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private User childWithParent(String childId, String parentId, String name) {
        User child = new User();
        child.setId(childId);
        child.setParentId(parentId);
        child.setChildName(name);
        return child;
    }

    private User childWithoutParent(String childId) {
        User child = new User();
        child.setId(childId);
        child.setParentId(null);
        return child;
    }
}
