package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.quiz.AnswerSubmission;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.QuizResult;
import com.pally.domain.quiz.Sm2Scheduler;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaEntity;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubmitQuizAnswersUseCase {

    private static final int XP_PER_CORRECT = 4;
    private static final int BASE_XP = 20;

    private final AvatarRepository avatarRepository;
    private final FlashcardRepository flashcardRepository;
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;
    private final QuizQuestionResultJpaRepository quizResultRepo;

    /**
     * Submits quiz answers, applies SM-2 scheduling to matching flashcards,
     * and returns score + XP earned.
     *
     * @param submission answers keyed by questionId → selectedIndex
     * @param correctMap questionId → correctIndex (passed from controller which held quiz state)
     */
    public QuizResult execute(AnswerSubmission submission, Map<String, Integer> correctMap) {
        return execute(submission, correctMap, Map.of());
    }

    /**
     * Same as {@link #execute(AnswerSubmission, Map)} but also records the
     * topic slug per question so the weak-topics report can group results.
     *
     * @param topicMap questionId → topic slug (may be empty/missing entries)
     */
    public QuizResult execute(AnswerSubmission submission,
                              Map<String, Integer> correctMap,
                              Map<String, String> topicMap) {
        if (!avatarRepository.existsByIdAndUserId(submission.avatarId(), submission.userId())) {
            throw new AvatarNotFoundException(submission.avatarId());
        }

        int correct = 0;
        for (Map.Entry<String, Integer> entry : submission.answers().entrySet()) {
            Integer correctIndex = correctMap.get(entry.getKey());
            boolean wasCorrect = correctIndex != null && correctIndex.equals(entry.getValue());
            if (wasCorrect) correct++;

            // Persist per-question result for error pattern analysis. Best-effort.
            try {
                QuizQuestionResultJpaEntity r = new QuizQuestionResultJpaEntity();
                r.setId(IdGenerator.newId());
                r.setUserId(submission.userId());
                r.setAvatarId(submission.avatarId());
                r.setQuestionId(entry.getKey());
                r.setTopicSlug(topicMap.get(entry.getKey())); // may be null
                r.setWasCorrect(wasCorrect);
                r.setCreatedAt(Instant.now());
                quizResultRepo.save(r);
            } catch (Exception ignored) {
                // never block scoring on result persistence
            }
        }

        int total = submission.answers().size();
        int xpEarned = BASE_XP + (correct * XP_PER_CORRECT);
        int starsEarned = Math.round(xpEarned * 0.5f);

        // Snapshot level before, persist XP+stars, then read the new level
        // so we can emit levelledUp = true exactly once per crossing.
        int oldLevel = userRepository.findById(submission.userId())
                .map(s -> s.level())
                .orElse(1);
        userRepository.addXpAndStars(submission.userId(), xpEarned, starsEarned);
        int newLevel = userRepository.findById(submission.userId())
                .map(s -> s.level())
                .orElse(oldLevel);
        boolean levelledUp = newLevel > oldLevel;

        // Update SM-2 for due flashcards based on performance
        updateFlashcardSchedules(submission.avatarId(), correct, total);

        // Activity + badges
        activityLogService.log(submission.userId(), submission.avatarId(),
                ActivityLogService.TYPE_QUIZ, 0, xpEarned);
        badgeService.grantFirstAction(submission.userId(), BadgeService.BadgeType.FIRST_QUIZ);
        badgeService.grantPerfectQuiz(submission.userId(), correct, total);
        badgeService.checkAndGrantMilestones(submission.userId());

        return new QuizResult(
                IdGenerator.newId(), correct, total, xpEarned, starsEarned,
                levelledUp, newLevel);
    }

    private void updateFlashcardSchedules(String avatarId, int correct, int total) {
        List<FlashCard> dueCards = flashcardRepository.findDueByAvatarId(avatarId);
        if (dueCards.isEmpty()) return;

        double ratio = total > 0 ? (double) correct / total : 0.5;
        CardRating rating = ratio >= 0.8 ? CardRating.EASY : ratio >= 0.5 ? CardRating.OKAY : CardRating.HARD;

        List<FlashCard> updated = dueCards.stream()
                .map(card -> Sm2Scheduler.applyRating(card, rating))
                .toList();
        flashcardRepository.saveAll(updated);
    }
}
