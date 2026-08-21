package com.pally.domain.metrics;

import com.pally.domain.metrics.dto.CohortMetrics;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cohort learning-outcome metrics. Read-model only — every query is a SELECT, nothing
 * is persisted, and no new table backs this.
 *
 * <p>Two honesty rules are enforced here rather than left to the caller, because both
 * failure modes are silent and both would mislead in a sales conversation:
 * <ol>
 *   <li>Dormant avatars (content, zero activity — typically auto-generated at compile
 *       time for accounts that never engaged) are NEVER in a rate denominator, and
 *       are ALWAYS reported. Counting them understates every rate; hiding them
 *       overstates the population.</li>
 *   <li>An empty denominator yields null + INSUFFICIENT_DATA, never 0%. "No student
 *       has reached a second review yet" and "0% of students retain anything" are
 *       opposite claims; only the first is supported when the table is empty.</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class CohortMetricsService {

    private final CohortMetricsRepository repo;

    /**
     * @param level   optional grade-level filter; null = all
     * @param subject optional subject filter; null = all
     */
    @Transactional(readOnly = true)
    public CohortMetrics compute(String level, String subject) {
        String lvl = blankToNull(level);
        String subj = blankToNull(subject);

        int allAvatars = repo.countAllAvatars(lvl, subj);
        int active = repo.countActiveAvatars(lvl, subj);
        int dormant = repo.countDormantAvatars(lvl, subj);

        // ACTIVATION — of every avatar created, how many ever did a learning action.
        CohortMetrics.Rate activation = CohortMetrics.Rate.of(active, allAvatars);

        // RETURN — of ACTIVE avatars (never all avatars: a dormant avatar cannot
        // "fail to return", it never arrived), how many were active on a later
        // Asia/Singapore calendar day.
        CohortMetrics.Rate returnRate =
                CohortMetrics.Rate.of(repo.countReturnedOnLaterDay(lvl, subj), active);

        // REPEAT REVIEW — of cards with any review, how many were reviewed again.
        // Denominator is 0 until flashcard_review has rows → null, not 0%.
        CohortMetrics.Rate repeatReview = CohortMetrics.Rate.of(
                repo.countRepeatReviewedCards(lvl, subj), repo.countReviewedCards(lvl, subj));

        // VERIFIED RETENTION — the claim everything rests on. Denominator is reviews
        // that found the card ALREADY in a successful streak (prev_repetitions > 0):
        // a genuine repeat recall, not a restart after a HARD lapse. Numerator is
        // those recalled successfully (SM-2 quality >= 3; HARD q=2 is a lapse).
        CohortMetrics.Rate verifiedRetention = CohortMetrics.Rate.of(
                repo.countRepeatRecallCorrect(lvl, subj), repo.countRepeatRecallAttempts(lvl, subj));

        CohortMetrics.MedianDays median = CohortMetrics.MedianDays.of(
                repo.medianDaysToSecondSession(lvl, subj), repo.countAvatarsWithSecondDay(lvl, subj));

        return new CohortMetrics(lvl, subj, active, dormant,
                activation, returnRate, repeatReview, verifiedRetention, median);
    }

    private String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
