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
import java.util.ArrayList;
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
        return execute(submission, correctMap, Map.of(), Map.of());
    }

    public QuizResult execute(AnswerSubmission submission,
                              Map<String, Integer> correctMap,
                              Map<String, String> topicMap) {
        return execute(submission, correctMap, topicMap, Map.of());
    }

    /**
     * Same as {@link #execute(AnswerSubmission, Map)} but also records topic
     * slugs and (optionally) self-reported confidence per question. When any
     * confidence value is supplied, the result carries a mastery matrix
     * (mastered / misconception / luckyGuess / knownGap).
     *
     * @param topicMap      questionId → topic slug (may be empty/missing entries)
     * @param confidenceMap questionId → "LOW" | "MEDIUM" | "HIGH"; empty when
     *                       the client is on legacy quiz mode
     */
    public QuizResult execute(AnswerSubmission submission,
                              Map<String, Integer> correctMap,
                              Map<String, String> topicMap,
                              Map<String, String> confidenceMap) {
        if (!avatarRepository.existsByIdAndUserId(submission.avatarId(), submission.userId())) {
            throw new AvatarNotFoundException(submission.avatarId());
        }

        int correct = 0;
        List<String> mastered = new ArrayList<>();
        List<String> misconception = new ArrayList<>();
        List<String> luckyGuess = new ArrayList<>();
        List<String> knownGap = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : submission.answers().entrySet()) {
            String questionId = entry.getKey();
            Integer correctIndex = correctMap.get(questionId);
            boolean wasCorrect = correctIndex != null && correctIndex.equals(entry.getValue());
            if (wasCorrect) correct++;

            String topic = topicMap.get(questionId);
            String label = topic != null ? topic : questionId;
            String confidence = confidenceMap.get(questionId);

            // Classify into the 2x2 matrix when we have a confidence reading.
            if (confidence != null) {
                boolean isConfident = "HIGH".equalsIgnoreCase(confidence);
                if (wasCorrect && isConfident) {
                    mastered.add(label);
                } else if (!wasCorrect && isConfident) {
                    misconception.add(label);
                } else if (wasCorrect) {
                    luckyGuess.add(label);
                } else {
                    knownGap.add(label);
                }
            }

            // Persist per-question result for error pattern analysis. Best-effort.
            try {
                QuizQuestionResultJpaEntity r = new QuizQuestionResultJpaEntity();
                r.setId(IdGenerator.newId());
                r.setUserId(submission.userId());
                r.setAvatarId(submission.avatarId());
                r.setQuestionId(questionId);
                r.setTopicSlug(topic); // may be null
                r.setWasCorrect(wasCorrect);
                r.setConfidence(confidence);
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

        QuizResult.MasteryMatrix matrix = null;
        if (!confidenceMap.isEmpty()) {
            // Misconceptions are the highest-priority remediation target — they
            // are the hidden wrong-knowledge that compounds; bias the priority
            // pointer toward them, fall back to known gaps if none.
            String priority = misconception.isEmpty()
                    ? (knownGap.isEmpty() ? null : knownGap.get(0))
                    : misconception.get(0);
            matrix = new QuizResult.MasteryMatrix(
                    mastered, misconception, luckyGuess, knownGap, priority);
        }

        return new QuizResult(
                IdGenerator.newId(), correct, total, xpEarned, starsEarned,
                levelledUp, newLevel, matrix);
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
