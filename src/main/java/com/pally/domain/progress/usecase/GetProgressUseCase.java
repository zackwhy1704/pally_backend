package com.pally.domain.progress.usecase;

import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GetProgressUseCase {

    private final UserRepository userRepository;
    private final FlashcardRepository flashcardRepository;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;

    public ProgressSummary execute(String userId) {
        userRepository.ensureUserExists(userId);

        UserStats stats = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        int xpToNext = ProgressSummary.xpToNext(stats.xp());

        // Milestone badges may need to be granted from the latest state
        // (e.g., user just crossed level 5 from a quiz). Cheap idempotent call.
        badgeService.checkAndGrantMilestones(userId);

        return new ProgressSummary(
                userId,
                stats.xp(),
                stats.level(),
                xpToNext,
                stats.streakDays(),
                stats.stars(),
                0, // totalFlashcards - would need aggregation across avatars
                0, // dueFlashcards
                0, // totalQuizzesTaken
                0, // averageScore
                activityLogService.minutesPerDayLast7(userId),
                badgeService.getBadges(userId)
        );
    }
}
