package com.pally.domain.subscription;

import com.pally.domain.progress.LevelRewards;

/**
 * Canonical entitlements record — the single source of truth for every
 * tier limit. All gates in the system must read from here; no tier limit
 * may be hardcoded at a call site.
 *
 * <p>UNLIMITED sentinel = -1. Callers can check with
 * {@link #isUnlimited(int)} rather than comparing against -1 directly.
 *
 * <p>Note: the enum value {@code FREE} corresponds to the "SPARK" tier in
 * the product specification. The alias is preserved for backward
 * compatibility with existing subscription-row plan strings.
 */
public record Entitlements(
        int chatsPerDay,          // -1 = UNLIMITED
        int maxMochis,            // -1 = UNLIMITED; FREE uses level-aware calc
        int maxStudents,          // max linked children/students the PAYER may have
        ParentDashboard parentDashboard,
        boolean groups,
        boolean priorityAi,
        boolean quizFlashcards,   // always true for all tiers currently
        boolean studyPlan,        // always true for all tiers currently
        int monthlyUploadCap,     // -1 = UNLIMITED; accepted (compile-triggering) uploads / 30d
        int monthlyChunkCompiles  // -1 = UNLIMITED; chapter-chunk compiles / 30d (successful)
) {

    public enum ParentDashboard { NONE, FAMILY_WIDE, PER_STUDENT }

    /**
     * Default FREE upload cap. The compile (~25c on Gemini) is the expensive op,
     * so unlimited free uploads bleed cost against zero revenue. This is the
     * canonical default; {@code subscription.free.upload-cap} tunes it at the
     * enforcement layer without a deploy.
     */
    public static final int DEFAULT_FREE_UPLOAD_CAP = 5;

    /**
     * Default FREE chapter-chunk compile cap per rolling 30d. Each chunk compile is
     * the same expensive op an upload triggers, so FREE gets a small allowance and
     * the picker's return loop nudges upgrades. Config-tunable at the guard via
     * {@code subscription.free.chunk-compile-cap}. Centre-source students inherit a
     * PAID tier server-side, so B2B lands on the unlimited arm — same guard code path.
     */
    public static final int DEFAULT_FREE_CHUNK_COMPILE_CAP = 5;

    /**
     * Returns the full set of entitlements for a tier, taking the user's
     * level into account for the FREE (SPARK) tier's Mochi cap.
     * For paid tiers, level is ignored.
     */
    public static Entitlements forTier(SubscriptionTier tier, int userLevel) {
        return switch (tier) {
            case FREE   -> new Entitlements(20,  LevelRewards.freeTutorCap(userLevel), 1, ParentDashboard.NONE,        false, false, true, true, DEFAULT_FREE_UPLOAD_CAP, DEFAULT_FREE_CHUNK_COMPILE_CAP);
            case PRO    -> new Entitlements(100, 5,                                    1, ParentDashboard.FAMILY_WIDE, true,  false, true, true, 50,  100);
            case MAX    -> new Entitlements(-1,  -1,                                   1, ParentDashboard.FAMILY_WIDE, true,  true,  true, true, -1,  -1);
            case FAMILY -> new Entitlements(-1,  -1,                                   4, ParentDashboard.FAMILY_WIDE, true,  true,  true, true, -1,  -1);
        };
    }

    /**
     * Convenience overload without level — for gates where level is
     * irrelevant (groups, maxStudents, parentDashboard, etc.).
     * Uses level=1 so FREE Mochi cap defaults to 1 (the minimum).
     */
    public static Entitlements forTier(SubscriptionTier tier) {
        return forTier(tier, 1);
    }

    /** Returns true if the given value represents "unlimited" (-1 sentinel). */
    public static boolean isUnlimited(int value) {
        return value == -1;
    }
}
