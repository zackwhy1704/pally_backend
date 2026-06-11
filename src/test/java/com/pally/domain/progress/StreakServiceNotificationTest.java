package com.pally.domain.progress;

import com.pally.domain.notification.MilestoneNotifier;
import com.pally.infrastructure.persistence.activity.DailyActivityDayJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreakServiceNotificationTest {

    @Mock private UserJpaRepository userRepo;
    @Mock private DailyActivityDayJpaRepository dayRepo;
    @Mock private BadgeService badgeService;
    @Mock private MilestoneNotifier milestoneNotifier;

    private StreakService service;

    @BeforeEach
    void setUp() {
        service = new StreakService(userRepo, dayRepo, badgeService, milestoneNotifier);
    }

    @Test
    void recordActiveDay_reachingMilestone7_notifiesParent() {
        // User on a 6-day streak, last active yesterday, hitting day 7
        // Pre-celebrate milestone 3 so the loop reaches 7
        UserJpaEntity user = buildUser("u1", 6,
                LocalDate.now(ZoneOffset.UTC).minusDays(1));
        user.setStreakMilestonesReached("3");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));

        service.recordActiveDay("u1");

        verify(milestoneNotifier).onStreakMilestone("u1", 7);
    }

    @Test
    void recordActiveDay_reachingMilestone3_doesNotNotify() {
        // Milestone 3 is below the threshold (7+), so no push
        UserJpaEntity user = buildUser("u1", 2,
                LocalDate.now(ZoneOffset.UTC).minusDays(1));
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));

        service.recordActiveDay("u1");

        verify(milestoneNotifier, never()).onStreakMilestone(anyString(), anyInt());
    }

    @Test
    void recordActiveDay_normalDay_noNotification() {
        // Day 5, no milestone (3 already celebrated)
        UserJpaEntity user = buildUser("u1", 4,
                LocalDate.now(ZoneOffset.UTC).minusDays(1));
        user.setStreakMilestonesReached("3");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));

        service.recordActiveDay("u1");

        verify(milestoneNotifier, never()).onStreakMilestone(anyString(), anyInt());
    }

    @Test
    void recordActiveDay_milestoneAlreadyCelebrated_noNotification() {
        // User already celebrated milestone 7 in a previous streak
        UserJpaEntity user = buildUser("u1", 6,
                LocalDate.now(ZoneOffset.UTC).minusDays(1));
        user.setStreakMilestonesReached("3,7");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));

        service.recordActiveDay("u1");

        verify(milestoneNotifier, never()).onStreakMilestone(anyString(), anyInt());
    }

    @Test
    void recordActiveDay_notificationThrows_doesNotBreakStreak() {
        // MilestoneNotifier throws, but streak should still update
        // Pre-celebrate milestone 3 so the loop reaches 7 and triggers notification
        UserJpaEntity user = buildUser("u1", 6,
                LocalDate.now(ZoneOffset.UTC).minusDays(1));
        user.setStreakMilestonesReached("3");
        when(userRepo.findById("u1")).thenReturn(Optional.of(user));
        doThrow(new RuntimeException("FCM down"))
                .when(milestoneNotifier).onStreakMilestone(anyString(), anyInt());

        // Should not throw
        service.recordActiveDay("u1");

        // Streak should still be saved
        verify(userRepo).save(user);
    }

    private UserJpaEntity buildUser(String id, int streakDays, LocalDate lastActive) {
        UserJpaEntity user = UserJpaEntity.newUser(id);
        user.setStreakDays(streakDays);
        user.setLastActiveDate(lastActive);
        user.setStreakMilestonesReached(null);
        user.setStreakFreezes(1);
        user.setLongestStreak(streakDays);
        return user;
    }
}
