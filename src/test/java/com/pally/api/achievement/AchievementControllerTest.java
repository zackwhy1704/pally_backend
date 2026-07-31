package com.pally.api.achievement;

import com.pally.domain.progress.ActivityLogService;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.badge.UserBadgeJpaEntity;
import com.pally.infrastructure.persistence.badge.UserBadgeJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AchievementController} — catalogue shape,
 * earned/un-earned branching, and the progress computation per badge family.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AchievementControllerTest {

    @Mock UserJpaRepository userRepo;
    @Mock UserBadgeJpaRepository badgeRepo;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;
    @Mock ActivityLogJpaRepository activityRepo;

    @InjectMocks AchievementController controller;

    private static final String USER_ID = "user-1";

    private UserJpaEntity userWithStats(int streak, int level) {
        var u = new UserJpaEntity();
        u.setLevel(level);
        u.setStreakDays(streak);
        return u;
    }

    private UserJpaEntity userWithLocale(String locale) {
        var u = new UserJpaEntity();
        u.setLevel(1);
        u.setPreferredLocale(locale);
        return u;
    }

    @BeforeEach
    void defaults() {
        when(userRepo.findById(USER_ID)).thenReturn(
                Optional.of(userWithStats(0, 1)));
        when(badgeRepo.findByUserId(USER_ID)).thenReturn(List.of());
        when(quizResultRepo.countCorrectByUserId(USER_ID)).thenReturn(0L);
        when(activityRepo.countByTypeBetween(
                eq(USER_ID), eq(ActivityLogService.TYPE_PHOTO), any(), any()))
                .thenReturn(0);
    }

    @Test
    void list_unknownUser_throws404() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.list("ghost"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("User not found");
    }

    @Test
    void list_noEarnedBadges_allUnearnedWithProgressZero() {
        var body = controller.list(USER_ID).getBody().data();
        @SuppressWarnings("unchecked")
        var achievements = (List<Map<String, Object>>) body.get("achievements");

        assertThat(achievements).isNotEmpty();
        assertThat(body.get("earnedCount")).isEqualTo(0);
        achievements.forEach(a -> assertThat(a.get("earned")).isEqualTo(false));
    }

    @Test
    void list_earnedBadge_showsEarnedTrueAndFullProgress() {
        var badge = new UserBadgeJpaEntity();
        badge.setBadgeType("STREAK_3");
        badge.setEarnedAt(Instant.parse("2025-01-01T00:00:00Z"));
        when(badgeRepo.findByUserId(USER_ID)).thenReturn(List.of(badge));

        var body = controller.list(USER_ID).getBody().data();
        @SuppressWarnings("unchecked")
        var achievements = (List<Map<String, Object>>) body.get("achievements");
        assertThat(body.get("earnedCount")).isEqualTo(1);

        var streak3 = achievements.stream()
                .filter(a -> "STREAK_3".equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(streak3.get("earned")).isEqualTo(true);
        assertThat(streak3.get("earnedAt")).asString()
                .startsWith("2025-01-01");
        // Earned badge always renders full progress
        assertThat((int) streak3.get("progress")).isEqualTo((int) streak3.get("target"));
    }

    @Test
    void list_streak7User_progressReflectsCurrentStreak() {
        when(userRepo.findById(USER_ID)).thenReturn(
                Optional.of(userWithStats(5, 1)));

        var body = controller.list(USER_ID).getBody().data();
        @SuppressWarnings("unchecked")
        var achievements = (List<Map<String, Object>>) body.get("achievements");

        var streak7 = achievements.stream()
                .filter(a -> "STREAK_7".equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(streak7.get("progress")).isEqualTo(5);
        assertThat(streak7.get("earned")).isEqualTo(false);
    }

    @Test
    void list_quizCorrect50_progressCappedAtTarget() {
        when(quizResultRepo.countCorrectByUserId(USER_ID)).thenReturn(120L);

        var body = controller.list(USER_ID).getBody().data();
        @SuppressWarnings("unchecked")
        var achievements = (List<Map<String, Object>>) body.get("achievements");

        var quiz50 = achievements.stream()
                .filter(a -> "QUIZ_CORRECT_50".equals(a.get("id")))
                .findFirst().orElseThrow();
        // progress is capped at target (50), not the raw 120
        assertThat((int) quiz50.get("progress")).isEqualTo(50);
    }

    @Test
    void list_totalCountMatchesCatalogSize() {
        var body = controller.list(USER_ID).getBody().data();
        @SuppressWarnings("unchecked")
        var achievements = (List<Map<String, Object>>) body.get("achievements");
        assertThat(body.get("totalCount")).isEqualTo(achievements.size());
    }

    @Test
    void list_defaultLocale_returnsEnglishNameAndDescription() {
        // userWithStats() never sets preferredLocale — the entity default
        // ("en") governs, proving an existing/unmigrated user sees no change.
        var body = controller.list(USER_ID).getBody().data();
        @SuppressWarnings("unchecked")
        var achievements = (List<Map<String, Object>>) body.get("achievements");
        var streak3 = achievements.stream()
                .filter(a -> "STREAK_3".equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(streak3.get("name")).isEqualTo("On a Roll");
        assertThat(streak3.get("description")).isEqualTo("3-day streak");
    }

    @Test
    void list_zhLocale_returnsChineseNameAndDescription() {
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(userWithLocale("zh")));

        var body = controller.list(USER_ID).getBody().data();
        @SuppressWarnings("unchecked")
        var achievements = (List<Map<String, Object>>) body.get("achievements");
        var streak3 = achievements.stream()
                .filter(a -> "STREAK_3".equals(a.get("id")))
                .findFirst().orElseThrow();
        assertThat(streak3.get("name")).isNotEqualTo("On a Roll");
        assertThat(streak3.get("description")).isNotEqualTo("3-day streak");
    }
}
