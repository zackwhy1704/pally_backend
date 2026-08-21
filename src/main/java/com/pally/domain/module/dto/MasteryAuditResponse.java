package com.pally.domain.module.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Read-model exposing WHY a module's mastery number is what it is: the headline
 * score alongside the evidence behind it, broken down by how much that evidence
 * can actually be trusted.
 *
 * <p>Derived entirely at read time from {@code module_progress} — nothing new is
 * persisted. Deliberately keeps trust and performance SEPARATE: unlike
 * {@code ModuleExamReadinessService#getExamPrep}, which reports
 * {@code weight × score} and so cannot tell "scored badly" apart from "scored well
 * but self-reported", every tier here carries its raw count AND its weighted
 * contribution as distinct fields. That distinguishability is the product claim.
 *
 * <p>Deliberately ABSENT: a groundedness status. {@code verificationJson} is written
 * only when the groundedness gate flags a problem, so its absence conflates
 * verified-clean / never-checked / check-threw-and-failed-open / no-extractable-text.
 * Emitting a field that cannot distinguish those would be a claim the work layer
 * does not implement.
 */
public record MasteryAuditResponse(
        String moduleId,
        String moduleTitle,

        /**
         * The persisted {@code LearningModule.masteryPct}, clamped to the documented
         * 0–100 contract (same clamp {@code getResults} applies) so a legacy or
         * mis-written row can never render as the historical >100% bug class.
         */
        BigDecimal masteryPct,

        /**
         * EVERY {@code module_progress} row for this (student, module) — including
         * LEARN completion markers and UNGRADED rows that contribute nothing to
         * mastery. This is "how much work exists", NOT "what produced the score".
         */
        int evidenceCount,

        /**
         * The subset of {@code evidenceCount} that actually fed {@code masteryPct},
         * per {@code ModuleProgressionService#contributesToMastery}. Reported
         * separately because assuming evidenceCount produced the score is the
         * easiest way to over-read this endpoint: a module can carry plenty of
         * evidence and still have almost none of it graded.
         */
        int masteryContributingCount,

        /** Most recent {@code completedAt} across all rows; null when none carry one. */
        Instant lastEvidenceAt,

        /** One entry per trust tier, always present even at count 0, so a caller
         *  can never mistake an absent tier for an unreported one. */
        List<TrustTier> trustBreakdown
) {

    /**
     * @param tier                  DETERMINISTIC / SELF_REPORT / UNGRADED, or
     *                              LEGACY_UNTYPED for pre-signal-typing rows.
     * @param count                 rows in this tier (all rows, not just contributing).
     * @param contributingCount     rows in this tier that actually fed masteryPct.
     * @param weight                the trust weight applied to this tier.
     * @param weightedContribution  {@code contributingCount × weight} — the evidence
     *                              MASS this tier contributed. Kept apart from the
     *                              scores themselves so trust never silently
     *                              masquerades as performance.
     */
    public record TrustTier(
            String tier,
            int count,
            int contributingCount,
            double weight,
            double weightedContribution
    ) {}

    /**
     * Bucket name for rows whose {@code signal_type} is null (persisted before
     * V111 signal-typing). Surfaced as its OWN tier rather than folded into
     * DETERMINISTIC: {@code GradingWeights.weightFor(null)} returns full weight 1.0,
     * so folding them in would report server-verified evidence for rows that were
     * never verified — the same "cannot distinguish verified from unchecked" defect
     * that got groundedness excluded from this response.
     */
    public static final String TIER_LEGACY_UNTYPED = "LEGACY_UNTYPED";
}
