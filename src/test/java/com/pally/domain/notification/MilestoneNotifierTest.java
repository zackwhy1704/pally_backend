package com.pally.domain.notification;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.push.FcmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MilestoneNotifierTest {

    @Mock private UserJpaRepository userRepo;
    @Mock private FcmService fcmService;

    private MilestoneNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new MilestoneNotifier(userRepo, fcmService);
    }

    // ── onStreakMilestone ────────────────────────────────────────────────

    @Test
    void onStreakMilestone_childHasParent_sendsPush() {
        UserJpaEntity child = childWithParent("child-1", "parent-1", "Alice");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        notifier.onStreakMilestone("child-1", 7);

        verify(fcmService).sendToUser(eq("parent-1"), contains("Alice"), anyString());
    }

    @Test
    void onStreakMilestone_childHasNoParent_noOp() {
        UserJpaEntity child = childWithoutParent("child-1");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        notifier.onStreakMilestone("child-1", 7);

        verifyNoInteractions(fcmService);
    }

    @Test
    void onStreakMilestone_childNotFound_noOp() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        notifier.onStreakMilestone("ghost", 7);

        verifyNoInteractions(fcmService);
    }

    // ── onModuleCompleted ───────────────────────────────────────────────

    @Test
    void onModuleCompleted_childHasParent_sendsPush() {
        UserJpaEntity child = childWithParent("child-1", "parent-1", "Bob");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        notifier.onModuleCompleted("child-1", "Photosynthesis", 0.92);

        verify(fcmService).sendToUser(
                eq("parent-1"),
                contains("Bob"),
                contains("Photosynthesis"));
    }

    @Test
    void onModuleCompleted_childHasNoParent_noOp() {
        UserJpaEntity child = childWithoutParent("child-1");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        notifier.onModuleCompleted("child-1", "Math Basics", 0.85);

        verifyNoInteractions(fcmService);
    }

    // ── onInactiveChild ─────────────────────────────────────────────────

    @Test
    void onInactiveChild_childHasParent_alertSuppressedWithoutPositives() {
        // The 3:1 ratio requires 3 positives before the first alert
        UserJpaEntity child = childWithParent("child-1", "parent-1", "Charlie");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        notifier.onInactiveChild("child-1", "Charlie");

        // Alert is suppressed because 0 positives < (0+1)*3
        verify(fcmService, never()).sendToUser(anyString(), anyString(), anyString());
    }

    @Test
    void onInactiveChild_childHasNoParent_noOp() {
        UserJpaEntity child = childWithoutParent("child-1");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        notifier.onInactiveChild("child-1", "Charlie");

        verifyNoInteractions(fcmService);
    }

    @Test
    void onInactiveChild_childNotFound_noOp() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        notifier.onInactiveChild("ghost", "Ghost");

        verifyNoInteractions(fcmService);
    }

    @Test
    void onInactiveChild_alertSuppressedByThreeToOneRatio() {
        // We need 3 positive pushes before 1 alert is allowed
        UserJpaEntity child = childWithParent("child-1", "parent-1", "Dana");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        // No positives sent yet, so first alert should be suppressed
        notifier.onInactiveChild("child-1", "Dana");

        // The alert should be suppressed because positive count is 0 < (0+1)*3
        verify(fcmService, never()).sendToUser(anyString(), anyString(), anyString());
    }

    @Test
    void onInactiveChild_alertAllowedAfterEnoughPositives() {
        UserJpaEntity child = childWithParent("child-1", "parent-1", "Eve");
        when(userRepo.findById("child-1")).thenReturn(Optional.of(child));

        // Send 3 positive notifications first via streak milestones
        notifier.onStreakMilestone("child-1", 7);
        notifier.onStreakMilestone("child-1", 14);
        notifier.onStreakMilestone("child-1", 30);

        // Now an alert should be allowed (3 positives >= (0+1)*3)
        notifier.onInactiveChild("child-1", "Eve");

        // 3 positive pushes + 1 alert push = 4 total
        verify(fcmService, times(4)).sendToUser(eq("parent-1"), anyString(), anyString());
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private UserJpaEntity childWithParent(String childId, String parentId, String name) {
        UserJpaEntity child = UserJpaEntity.newUser(childId);
        child.setParentId(parentId);
        child.setChildName(name);
        child.setDisplayName(name);
        return child;
    }

    private UserJpaEntity childWithoutParent(String childId) {
        UserJpaEntity child = UserJpaEntity.newUser(childId);
        child.setParentId(null);
        return child;
    }
}
