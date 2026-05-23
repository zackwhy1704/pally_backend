package com.pally.domain.progress.usecase;

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

    public ProgressSummary execute(String userId) {
        userRepository.ensureUserExists(userId);

        UserStats stats = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        int xpToNext = ProgressSummary.xpToNext(stats.xp());

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
                0  // averageScore
        );
    }
}
