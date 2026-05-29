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
    private final com.pally.domain.knowledge.WikiRepository wikiRepository;
    private final com.pally.domain.referral.ReferralService referralService;

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
        // R1 — accumulate source-slug feedback so the wiki's certainty
        // scores can self-correct from real student performance.
        List<String> correctSlugs = new ArrayList<>();
        List<String> wrongSlugs = new ArrayList<>();

        for (Map.Entry<String, Integer> entry : submission.answers().entrySet()) {
            String questionId = entry.getKey();
            Integer correctIndex = correctMap.get(questionId);
            boolean wasCorrect = correctIndex != null && correctIndex.equals(entry.getValue());
            if (wasCorrect) correct++;

            String topic = topicMap.get(questionId);
            String label = topic != null ? topic : questionId;
            String confidence = confidenceMap.get(questionId);

            if (topic != null && !topic.isBlank()) {
                if (wasCorrect) {
                    correctSlugs.add(topic);
                } else {
                    wrongSlugs.add(topic);
                }
            }

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

        // Persist XP+stars and capture the level transition in one round-trip.
        // (Previously this issued three queries to compute the same thing.)
        var credit = userRepository.addXpAndStars(
                submission.userId(), xpEarned, starsEarned);
        int newLevel = credit.newLevel();
        boolean levelledUp = credit.levelledUp();

        // Update SM-2 for due flashcards based on performance
        updateFlashcardSchedules(submission.avatarId(), correct, total);

        // Activity + badges
        activityLogService.log(submission.userId(), submission.avatarId(),
                ActivityLogService.TYPE_QUIZ, 0, xpEarned);
        badgeService.grantFirstAction(submission.userId(), BadgeService.BadgeType.FIRST_QUIZ);
        badgeService.grantPerfectQuiz(submission.userId(), correct, total);
        badgeService.checkAndGrantMilestones(submission.userId());

        // Referral activation — idempotent + no-op when nothing pending.
        // Has to run AFTER the per-question results above so the user has
        // a real activity footprint by the time the bonus credits land.
        try {
            referralService.onFirstQuizAnswer(submission.userId());
        } catch (Exception e) {
            log.warn("[Referral] activation hook skipped: {}", e.getMessage());
        }

        // R1 — self-correcting knowledge base. Correct answers reinforce the
        // source page (small +); wrong answers shake confidence harder and
        // flag the page for review because misconceptions compound fast.
        // Closing the loop: a topic answered correctly AND never wrong in
        // this quiz clears its review flag — without this, the flag would
        // stick forever even after the student masters the topic.
        try {
            if (!correctSlugs.isEmpty()) {
                wikiRepository.adjustCertainty(submission.avatarId(),
                        correctSlugs.stream().distinct().toList(), +0.05);
            }
            if (!wrongSlugs.isEmpty()) {
                List<String> distinctWrong = wrongSlugs.stream().distinct().toList();
                wikiRepository.adjustCertainty(submission.avatarId(),
                        distinctWrong, -0.10);
                wikiRepository.setReviewRequired(submission.avatarId(),
                        distinctWrong, true);
                log.info("[Harness] Flagged {} pages for review after wrong "
                        + "answers: {}", distinctWrong.size(), distinctWrong);
            }
            // Clear the flag for topics that were correct AND never wrong in
            // this quiz. Wrong wins on ties so a single mistake keeps the
            // page flagged even if the student also got it right elsewhere.
            if (!correctSlugs.isEmpty()) {
                List<String> cleanlyCorrect = correctSlugs.stream()
                        .distinct()
                        .filter(s -> !wrongSlugs.contains(s))
                        .toList();
                if (!cleanlyCorrect.isEmpty()) {
                    wikiRepository.setReviewRequired(submission.avatarId(),
                            cleanlyCorrect, false);
                    log.info("[Harness] Cleared review flag on {} mastered "
                            + "pages: {}", cleanlyCorrect.size(), cleanlyCorrect);
                }
            }
        } catch (Exception e) {
            // Never block scoring on harness feedback — log + carry on.
            log.warn("[Harness] Certainty feedback failed: {}", e.getMessage());
        }

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
