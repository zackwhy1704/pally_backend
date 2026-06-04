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
        boolean studyPlan         // always true for all tiers currently
) {

    public enum ParentDashboard { NONE, FAMILY_WIDE, PER_STUDENT }

    /**
     * Returns the full set of entitlements for a tier, taking the user's
     * level into account for the FREE (SPARK) tier's Mochi cap.
     * For paid tiers, level is ignored.
     */
    public static Entitlements forTier(SubscriptionTier tier, int userLevel) {
        return switch (tier) {
            case FREE   -> new Entitlements(20,  LevelRewards.freeTutorCap(userLevel), 1,  ParentDashboard.NONE,        false, false, true, true);
            case PRO    -> new Entitlements(100, 5,                                    1,  ParentDashboard.FAMILY_WIDE, true,  false, true, true);
            case MAX    -> new Entitlements(-1,  -1,                                   1,  ParentDashboard.FAMILY_WIDE, true,  true,  true, true);
            case FAMILY -> new Entitlements(-1,  -1,                                   4,  ParentDashboard.FAMILY_WIDE, true,  true,  true, true);
            case CENTRE -> new Entitlements(-1,  -1,                                   15, ParentDashboard.PER_STUDENT, true,  true,  true, true);
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
