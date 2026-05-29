package com.pally.domain.progress.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class GetProgressUseCase {

    private final UserRepository userRepository;
    private final FlashcardRepository flashcardRepository;
    private final AvatarRepository avatarRepository;
    private final QuizQuestionResultJpaRepository quizResultRepo;
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

        // Real aggregations — replace the four hardcoded zeros so the
        // progress card actually reflects reality.
        List<Avatar> avatars = avatarRepository.findByUserId(userId);
        int totalFlashcards = 0;
        int dueFlashcards = 0;
        for (Avatar a : avatars) {
            totalFlashcards += flashcardRepository.countByAvatarId(a.getId());
            dueFlashcards += flashcardRepository.findDueByAvatarId(a.getId()).size();
        }

        int totalQuizzesTaken =
                (int) quizResultRepo.countDistinctDaysByUserId(userId);
        int averageScore = (int) Math.round(
                quizResultRepo.averageAccuracyByUserId(userId) * 100);

        return new ProgressSummary(
                userId,
                stats.xp(),
                stats.level(),
                xpToNext,
                stats.streakDays(),
                stats.stars(),
                totalFlashcards,
                dueFlashcards,
                totalQuizzesTaken,
                averageScore,
                activityLogService.minutesPerDayLast7(userId),
                badgeService.getBadges(userId)
        );
    }
}
