package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.weakness.WeaknessProfileService;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.user.UserRepository;
import com.pally.domain.quiz.AnswerSubmission;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.QuizResult;
import com.pally.domain.quiz.Sm2Scheduler;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaEntity;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizIdempotencyRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    /// Confidence-weighted certainty deltas — B-B2 fidelity upgrade over
    /// the flat ±0.05/-0.10 the harness used to apply. A confidently-wrong
    /// answer is a real misconception and should shake the page hardest;
    /// a low-confidence wrong is a known gap (still nudges down, gently);
    /// confidently-right reinforces more; a lucky guess barely moves the
    /// needle so we don't paper over weak understanding.
    private static final double DELTA_HIGH_CORRECT     = +0.08;
    private static final double DELTA_HIGH_WRONG       = -0.15;
    private static final double DELTA_LOW_CORRECT      = +0.02;
    private static final double DELTA_LOW_WRONG        = -0.03;
    private static final double DELTA_DEFAULT_CORRECT  = +0.05;
    private static final double DELTA_DEFAULT_WRONG    = -0.10;

    private final AvatarRepository avatarRepository;
    private final FlashcardRepository flashcardRepository;
    private final UserRepository userRepository;
    private final ActivityLogService activityLogService;
    private final BadgeService badgeService;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final com.pally.domain.knowledge.WikiRepository wikiRepository;
    private final com.pally.domain.referral.ReferralService referralService;
    private final com.pally.domain.progress.XpService xpService;
    private final AvatarSlotGuard avatarSlotGuard;
    private final QuizAnswerKeyRepository answerKeyRepository;
    private final WeaknessProfileService weaknessProfileService;
    /// Self-proxy so the best-effort per-question result write runs in its OWN
    /// REQUIRES_NEW transaction; a self-invocation would bypass the proxy and
    /// keep it inside the primary tx (the bug we are fixing).
    private final ObjectProvider<SubmitQuizAnswersUseCase> selfProvider;
    private final QuizIdempotencyRepository idempotencyRepository;
    private final com.pally.domain.learning.LearningEventRepository learningEventRepository;

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

    public QuizResult execute(AnswerSubmission submission,
                              Map<String, Integer> correctMap,
                              Map<String, String> topicMap,
                              Map<String, String> confidenceMap) {
        return execute(submission, correctMap, topicMap, confidenceMap, 0);
    }

    /// Idempotent submit: claims the key, grades ONCE, stores the result — all in
    /// ONE transaction. A concurrent retry with the same key blocks on the claim's
    /// unique index until this commits, then throws {@link DuplicateSubmissionException}
    /// (the caller returns the stored result). Grading therefore runs exactly once,
    /// so no double XP/stars and no duplicate analytics rows on a retry/race.
    @Transactional
    public QuizResult executeWithIdempotency(AnswerSubmission submission,
                                             Map<String, Integer> correctMap,
                                             Map<String, String> topicMap,
                                             Map<String, String> confidenceMap,
                                             int durationSeconds,
                                             String idempotencyKey) {
        idempotencyRepository.claim(submission.userId(), idempotencyKey);
        QuizResult result = execute(submission, correctMap, topicMap, confidenceMap, durationSeconds);
        idempotencyRepository.storeResult(submission.userId(), idempotencyKey, result);
        return result;
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
    /// Wrapped in @Transactional so the multi-write path (per-question
    /// results + XP/stars + SRS reschedule + referral activation +
    /// badges + activity log) commits atomically. Without this, a
    /// failure halfway through left us with XP credited but no quiz
    /// rows, or a referral marked activated without the reward landing.
    @Transactional
    public QuizResult execute(AnswerSubmission submission,
                              Map<String, Integer> correctMap,
                              Map<String, String> topicMap,
                              Map<String, String> confidenceMap,
                              int durationSeconds) {
        // Fix 2: Slot guard — locked avatars cannot have quiz answers submitted.
        avatarSlotGuard.requireActive(submission.avatarId(), submission.userId());

        // GRADE INTEGRITY: re-derive correctness from the SERVER-held answer key
        // (persisted at quiz generation), NOT the client-supplied correctMap.
        // A tampered client can post an all-correct map; the centre's teacher
        // analytics ("grasp") is AVG(was_correct) over these results, so a
        // client-authoritative was_correct is a B2B integrity hole. The client
        // map is only a fallback for quizzes generated before this shipped
        // (no persisted key yet) — logged so the transition window is visible.
        Map<String, QuizAnswerKeyRepository.AnswerKey> serverKeys =
                answerKeyRepository.findByQuestionIds(submission.answers().keySet());

        // Best-effort per-question result rows, persisted AFTER the primary
        // commit in a REQUIRES_NEW tx so a failure here cannot roll back the
        // student's score/XP (Spring marks a tx rollback-only on a caught
        // persistence exception — the transaction-poisoning bug).
        List<QuizQuestionResultJpaEntity> pendingResults = new ArrayList<>();

        // Per-question feedback returned POST-submit — for teacher-graded quizzes
        // this is the ONLY place the correct answer is revealed (it's withheld
        // from the served question), so the student still gets feedback.
        List<QuizResult.QuestionFeedback> feedback = new ArrayList<>();

        int correct = 0;
        List<String> mastered = new ArrayList<>();
        List<String> misconception = new ArrayList<>();
        List<String> luckyGuess = new ArrayList<>();
        List<String> knownGap = new ArrayList<>();
        // R1 — accumulate source-slug feedback so the wiki's certainty
        // scores can self-correct from real student performance.
        List<String> correctSlugs = new ArrayList<>();
        List<String> wrongSlugs = new ArrayList<>();
        // B-B2 — per-slug delta sum so we can apply ONE adjust per slug,
        // weighted by the per-question confidence reading. Falls back to
        // the flat default deltas when the question has no confidence.
        java.util.Map<String, Double> slugDeltas = new java.util.HashMap<>();
        // Per-slug correctness for this quiz, so SM-2 reschedules each due card
        // by ITS topic's result instead of the whole-quiz ratio.
        java.util.Map<String, List<Boolean>> questionsForSlug = new java.util.HashMap<>();

        for (Map.Entry<String, Integer> entry : submission.answers().entrySet()) {
            String questionId = entry.getKey();
            // Server key is authoritative; the client map is a fallback only for
            // pre-existing quizzes that have no persisted key yet. The
            // explanation comes from the server key (the client never had it for
            // a teacher-graded quiz) and is revealed here, post-submit.
            final QuizAnswerKeyRepository.AnswerKey key = serverKeys.get(questionId);
            Integer correctIndex;
            String explanation;
            if (key != null) {
                correctIndex = key.correctIndex();
                explanation = key.explanation();
            } else {
                correctIndex = correctMap.get(questionId);
                explanation = null;
                log.warn("[Quiz] No server answer key for question={} (user={} "
                        + "avatar={}) — grading from client map this once; "
                        + "teacher analytics for this row are unverified",
                        questionId, submission.userId(), submission.avatarId());
            }
            boolean wasCorrect = correctIndex != null && correctIndex.equals(entry.getValue());
            if (wasCorrect) correct++;
            feedback.add(new QuizResult.QuestionFeedback(
                    questionId, wasCorrect, correctIndex, explanation));

            String topic = topicMap.get(questionId);
            String label = topic != null ? topic : questionId;
            String confidence = confidenceMap.get(questionId);

            if (topic != null && !topic.isBlank()) {
                if (wasCorrect) {
                    correctSlugs.add(topic);
                } else {
                    wrongSlugs.add(topic);
                }
                slugDeltas.merge(topic, weightedDelta(wasCorrect, confidence),
                        Double::sum);
                questionsForSlug
                        .computeIfAbsent(topic, k -> new ArrayList<>())
                        .add(wasCorrect);
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

            // Collect the per-question result row; persisted below in a
            // separate REQUIRES_NEW tx so a write failure can't poison the score.
            QuizQuestionResultJpaEntity r = new QuizQuestionResultJpaEntity();
            r.setId(IdGenerator.newId());
            r.setUserId(submission.userId());
            r.setAvatarId(submission.avatarId());
            r.setQuestionId(questionId);
            r.setTopicSlug(topic); // may be null
            r.setWasCorrect(wasCorrect);
            r.setConfidence(confidence);
            r.setCreatedAt(Instant.now());
            pendingResults.add(r);
        }

        // Isolate the best-effort analytics write from the primary score/XP
        // commit. REQUIRES_NEW (via the self-proxy) means a failure here rolls
        // back ONLY this inner tx; catching it out here keeps the primary tx
        // clean instead of UnexpectedRollbackException. Logged (not swallowed)
        // with context so a lost analytics row is visible in Railway logs.
        try {
            selfProvider.getObject().persistQuestionResultsInNewTx(pendingResults);
        } catch (Exception e) {
            log.warn("[Quiz] per-question result persistence failed — analytics "
                    + "gap (user={} avatar={} rows={}): {}",
                    submission.userId(), submission.avatarId(),
                    pendingResults.size(), e.getMessage());
        }

        int total = submission.answers().size();
        int baseXp = BASE_XP + (correct * XP_PER_CORRECT);

        // SRS + weak-topic bonuses: read the harness's "due" set + the
        // wiki's review-required set, then per CORRECT answer on a
        // matching topic, contribute (0.5×) or (0.25×) of the per-correct
        // XP. The bonuses are pre-decay so they get scaled like base XP
        // if the kid is on their 4th quiz of the day — tying the reward
        // to the learning algorithm without making it farm-resistant
        // (which would punish keen learners).
        java.util.Set<String> srsDueSlugs = new java.util.HashSet<>();
        try {
            for (var card : flashcardRepository.findDueByAvatarId(submission.avatarId())) {
                if (card.sourceSlug() != null) {
                    srsDueSlugs.add(card.sourceSlug());
                }
            }
        } catch (Exception e) {
            log.warn("[Quiz] SRS due lookup failed: {}", e.getMessage());
        }
        java.util.Set<String> weakSlugs = new java.util.HashSet<>();
        try {
            for (var page : wikiRepository.findReviewRequired(submission.avatarId())) {
                weakSlugs.add(page.getSlug());
            }
        } catch (Exception e) {
            log.warn("[Quiz] weak-topic lookup failed: {}", e.getMessage());
        }
        int srsBonusXp = 0;
        int weakBonusXp = 0;
        for (String slug : correctSlugs) {
            if (srsDueSlugs.contains(slug)) {
                srsBonusXp += Math.round(XP_PER_CORRECT * 0.5f);
            }
            if (weakSlugs.contains(slug)) {
                weakBonusXp += Math.round(XP_PER_CORRECT * 0.25f);
            }
        }

        // Route through XpService so decay (per-avatar same-day) + variety
        // bonus (first quiz of a new subject today) + SRS/weak bonuses +
        // L10 star multiplier + atomic credit all live in ONE place.
        // xpEarned/starsEarned below are the *granted* amounts (after
        // multipliers), not the raw request.
        var avatar = avatarRepository.findById(submission.avatarId()).orElse(null);
        var subject = avatar == null ? null : avatar.getSubject();
        var quizAward = xpService.awardForQuiz(
                submission.userId(), submission.avatarId(), subject,
                baseXp, srsBonusXp, weakBonusXp);
        int xpEarned = quizAward.xpGranted();
        int starsEarned = quizAward.starsGranted();
        var credit = quizAward.creditResult();
        int newLevel = credit.newLevel();
        boolean levelledUp = credit.levelledUp();

        // Update SM-2 for due flashcards — isolated in its own tx so a write
        // failure here can never mark the primary score/XP tx rollback-only.
        try {
            selfProvider.getObject().bestEffortFlashcardsInNewTx(
                    submission.avatarId(), questionsForSlug);
        } catch (Exception e) {
            log.warn("[SRS] flashcard schedule update failed — SRS state gap "
                    + "(avatar={}): {}", submission.avatarId(), e.getMessage());
        }

        // Activity + badges. durationSeconds is the (already-clamped) wall-clock
        // time the kid spent on this quiz — feeds the weekly minutes chart.
        activityLogService.log(submission.userId(), submission.avatarId(),
                ActivityLogService.TYPE_QUIZ, durationSeconds, xpEarned);
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

        // R1 — self-correcting knowledge base — isolated in its own tx. The
        // old try/catch wrapped REQUIRED code, which is the rollback-only trap:
        // a caught JPA exception still marks the shared tx rollback-only before
        // the catch runs. REQUIRES_NEW gives genuine isolation.
        try {
            selfProvider.getObject().bestEffortWikiCertaintyInNewTx(
                    submission.avatarId(), slugDeltas, wrongSlugs, correctSlugs);
        } catch (Exception e) {
            log.warn("[Harness] wiki certainty update failed — certainty gap "
                    + "(avatar={}): {}", submission.avatarId(), e.getMessage());
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

        // Refresh the weakness brain now that this submit changed quiz history —
        // the SAME guarded, best-effort trigger the module sites use
        // (ModuleProgressionService:411/:780). This closes the staleness window
        // between "quiz history changed" and "weak-set snapshot refreshed" so the
        // NEXT daily quiz's weak-first bias reads a current set. It changes only
        // WHEN the surface refreshes, not WHAT feeds it (the recompute still reads
        // quiz history only). The debounce makes repeat submits cheap.
        if (weaknessProfileService.isEnabled()) {
            try {
                weaknessProfileService.onMasteryUpdated(
                        submission.userId(), submission.avatarId());
            } catch (Exception ignored) {
                // best-effort — the quiz result stands
            }
        }

        return new QuizResult(
                IdGenerator.newId(), correct, total, xpEarned, starsEarned,
                levelledUp, newLevel, credit.unlockedRewardLabel(),
                matrix, List.copyOf(feedback));
    }

    /// Persists the best-effort per-question result rows in their OWN
    /// transaction. REQUIRES_NEW so a DataIntegrity/constraint failure here is
    /// isolated to this inner tx and can never mark the caller's primary
    /// score/XP transaction rollback-only. Called via the self-proxy
    /// ({@code selfProvider}); a self-invocation would bypass the proxy and
    /// defeat the propagation.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistQuestionResultsInNewTx(List<QuizQuestionResultJpaEntity> results) {
        if (results.isEmpty()) return;
        quizResultRepo.saveAll(results);
        for (QuizQuestionResultJpaEntity r : results) {
            learningEventRepository.save(com.pally.domain.learning.LearningEvent.of(
                    r.getUserId(), r.getAvatarId(),
                    com.pally.domain.learning.LearningEventSource.QUIZ,
                    com.pally.domain.learning.LearningEventProvenance.VERIFIED_SERVER_GRADED,
                    r.getTopicSlug(),
                    r.isWasCorrect() ? java.math.BigDecimal.ONE : java.math.BigDecimal.ZERO,
                    r.getId()));
        }
    }

    /// Records ONE graded question attempt outside a full quiz submission — e.g.
    /// a single boss-battle hit — through the SAME quiz_question_results +
    /// learning_event write path as {@link #persistQuestionResultsInNewTx}
    /// (never a second write path for the same fact), without the batch-quiz
    /// side effects (XP/badges/SRS reschedule/referral) that assume a whole
    /// quiz was submitted. Routes through the self-proxy for the same reason
    /// persistQuestionResultsInNewTx does: a plain `this.` call would bypass
    /// the REQUIRES_NEW proxy.
    public void recordSingleQuestionResult(String userId, String avatarId, String questionId,
                                            String topicSlug, boolean wasCorrect) {
        QuizQuestionResultJpaEntity r = new QuizQuestionResultJpaEntity();
        r.setId(IdGenerator.newId());
        r.setUserId(userId);
        r.setAvatarId(avatarId);
        r.setQuestionId(questionId);
        r.setTopicSlug(topicSlug);
        r.setWasCorrect(wasCorrect);
        r.setCreatedAt(Instant.now());
        selfProvider.getObject().persistQuestionResultsInNewTx(List.of(r));
    }

    /// SM-2 flashcard reschedule in its own tx. REQUIRES_NEW so a save failure
    /// here (e.g. constraint on a card that was deleted mid-quiz) can never
    /// mark the primary score/XP tx rollback-only. Called via selfProvider.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bestEffortFlashcardsInNewTx(
            String avatarId, Map<String, List<Boolean>> questionsForSlug) {
        updateFlashcardSchedules(avatarId, questionsForSlug);
    }

    /// R1 wiki-certainty feedback in its own tx. REQUIRES_NEW so a JPA failure
    /// here (e.g. UPDATE on a corpus avatar whose pages don't exist under the
    /// class-avatar ID) can never mark the primary score/XP tx rollback-only.
    /// Called via selfProvider.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bestEffortWikiCertaintyInNewTx(
            String avatarId,
            Map<String, Double> slugDeltas,
            List<String> wrongSlugs,
            List<String> correctSlugs) {
        for (var entry : slugDeltas.entrySet()) {
            double d = entry.getValue();
            if (d == 0.0) continue;
            wikiRepository.adjustCertainty(avatarId, List.of(entry.getKey()), d);
        }
        if (!wrongSlugs.isEmpty()) {
            List<String> distinctWrong = wrongSlugs.stream().distinct().toList();
            wikiRepository.setReviewRequired(avatarId, distinctWrong, true);
            log.info("[Harness] Flagged {} pages for review after wrong answers: {}",
                    distinctWrong.size(), distinctWrong);
        }
        if (!correctSlugs.isEmpty()) {
            List<String> cleanlyCorrect = correctSlugs.stream()
                    .distinct()
                    .filter(s -> !wrongSlugs.contains(s))
                    .toList();
            if (!cleanlyCorrect.isEmpty()) {
                wikiRepository.setReviewRequired(avatarId, cleanlyCorrect, false);
                log.info("[Harness] Cleared review flag on {} mastered pages: {}",
                        cleanlyCorrect.size(), cleanlyCorrect);
            }
        }
    }

    /// Reschedule due flashcards via SM-2, rating EACH card by the result of
    /// the quiz questions that share its source slug — not the whole-quiz ratio.
    /// A card whose slug had no questions in this quiz is left untouched (we
    /// have no fresh signal for it, so rescheduling it would be guessing).
    private void updateFlashcardSchedules(
            String avatarId, Map<String, List<Boolean>> questionsForSlug) {
        List<FlashCard> dueCards = flashcardRepository.findDueByAvatarId(avatarId);
        if (dueCards.isEmpty()) return;

        List<FlashCard> updated = new ArrayList<>();
        for (FlashCard card : dueCards) {
            if (card.sourceSlug() == null) continue;
            List<Boolean> results =
                    questionsForSlug.getOrDefault(card.sourceSlug(), List.of());
            if (results.isEmpty()) continue; // no questions this quiz for this slug
            double slugRatio = results.stream().mapToInt(b -> b ? 1 : 0).average().orElse(0.5);
            CardRating rating = slugRatio >= 0.8 ? CardRating.EASY
                    : slugRatio >= 0.5 ? CardRating.OKAY
                    : CardRating.HARD;
            updated.add(Sm2Scheduler.applyRating(card, rating));
        }
        if (!updated.isEmpty()) {
            flashcardRepository.saveAll(updated);
        }
    }

    /// Translate a (correct, confidence) pair into the certainty delta the
    /// harness should apply to the page that taught this topic.
    private double weightedDelta(boolean correct, String confidence) {
        if (confidence == null) {
            return correct ? DELTA_DEFAULT_CORRECT : DELTA_DEFAULT_WRONG;
        }
        String c = confidence.toUpperCase();
        if (c.equals("HIGH")) {
            return correct ? DELTA_HIGH_CORRECT : DELTA_HIGH_WRONG;
        }
        if (c.equals("LOW")) {
            return correct ? DELTA_LOW_CORRECT : DELTA_LOW_WRONG;
        }
        return correct ? DELTA_DEFAULT_CORRECT : DELTA_DEFAULT_WRONG;
    }
}
