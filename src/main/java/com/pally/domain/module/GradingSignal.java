package com.pally.domain.module;

/**
 * Trust class of a grading signal that may feed mastery/weakness. The weight
 * lives in {@link GradingWeights} (config-driven), not here, so pricing/policy
 * tuning needs no code change.
 *
 * <ul>
 *   <li>{@code DETERMINISTIC} — server graded a raw choice against the persisted
 *       answer key (MCQ, hot-take true/false). Full trust.</li>
 *   <li>{@code SELF_REPORT} — the student self-assessed an open-ended answer
 *       (YES/PARTLY/NO). Low trust; the system never asserted correctness.</li>
 *   <li>{@code UNGRADED} — no trustworthy signal: an evaluation error/timeout, a
 *       skip, a legacy unkeyed item, or a malformed submission. Contributes
 *       NOTHING to mastery — never a false 0. Persisted with score = NULL so it
 *       is distinguishable from a genuine 0.0.</li>
 * </ul>
 */
public enum GradingSignal {
    DETERMINISTIC,
    SELF_REPORT,
    UNGRADED
}
