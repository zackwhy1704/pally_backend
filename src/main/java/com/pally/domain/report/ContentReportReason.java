package com.pally.domain.report;

/**
 * Why a user reported an AI (Mochi) chat message. Deliberately SEPARATE from chat feedback
 * ({@code HELPFUL/WRONG/CONFUSED}) — a safety report is not a quality rating and must not be
 * conflated with one. UNSAFE is the child-safety signal; the others let a report still be filed
 * for wrong/other content without diluting the safety signal.
 */
public enum ContentReportReason {
    /** Inappropriate, unsafe, or upsetting content — the child-safety signal. */
    UNSAFE,
    /** Factually wrong or confusing/misleading answer. */
    WRONG_OR_MISLEADING,
    /** Anything else (free-text comment expected). */
    OTHER
}
