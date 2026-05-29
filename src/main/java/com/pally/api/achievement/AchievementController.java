package com.pally.api.achievement;

import com.pally.domain.progress.AchievementCatalog;
import com.pally.domain.progress.ActivityLogService;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.badge.UserBadgeJpaEntity;
import com.pally.infrastructure.persistence.badge.UserBadgeJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Achievement catalog + progress. Returns every badge the system knows
 * about with {@code earned}, optional {@code earnedAt}, and a
 * {@code progress}/{@code target} pair so the UI can render a generic
 * progress bar without any special-casing.
 */
@RestController
@RequestMapping("/api/v1/achievements")
@RequiredArgsConstructor
public class AchievementController {

    private final UserJpaRepository userRepo;
    private final UserBadgeJpaRepository badgeRepo;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final ActivityLogJpaRepository activityRepo;

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> list(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        Map<String, Instant> earnedMap = new HashMap<>();
        for (UserBadgeJpaEntity row : badgeRepo.findByUserId(userId)) {
            earnedMap.put(row.getBadgeType(), row.getEarnedAt());
        }

        // Cache the live counts once per request instead of running the
        // same SELECT for every badge that reads them.
        int streak = user.getStreakDays();
        int level = user.getLevel();
        long correctTotal = quizResultRepo.countCorrectByUserId(userId);
        Integer photoCount = activityRepo.countByTypeBetween(
                userId, ActivityLogService.TYPE_PHOTO,
                Instant.EPOCH, Instant.now().plusSeconds(60));

        List<Map<String, Object>> achievements = new ArrayList<>();
        for (var def : AchievementCatalog.all()) {
            Instant earnedAt = earnedMap.get(def.id());
            boolean earned = earnedAt != null;
            int progress = computeProgress(def, earned, streak, level,
                    correctTotal, photoCount);
            Map<String, Object> a = new HashMap<>();
            a.put("id", def.id());
            a.put("name", def.name());
            a.put("description", def.description());
            a.put("category", def.category().name());
            a.put("rarity", def.rarity().name());
            a.put("target", def.target());
            a.put("progress", Math.min(progress, def.target()));
            a.put("earned", earned);
            a.put("earnedAt", earnedAt == null ? null : earnedAt.toString());
            achievements.add(a);
        }

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "achievements", achievements,
                "earnedCount", earnedMap.size(),
                "totalCount", AchievementCatalog.all().size())));
    }

    private int computeProgress(
            AchievementCatalog.Definition def,
            boolean earned,
            int streak,
            int level,
            long correctTotal,
            Integer photoCount) {
        // Earned badges always render full progress so the bar looks tidy
        // even if the underlying counter shifted (e.g. a quiz row was
        // pruned).
        if (earned) return def.target();
        return switch (def.id()) {
            case "STREAK_3", "STREAK_7", "STREAK_30" -> streak;
            case "LEVEL_5", "LEVEL_10" -> level;
            case "QUIZ_CORRECT_50", "QUIZ_CORRECT_250" -> (int) correctTotal;
            case "PHOTOS_10" -> photoCount == null ? 0 : photoCount;
            default -> 0;
        };
    }
}
