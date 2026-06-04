package com.pally.domain.subscription;

/**
 * Centralised tier-aware limits.
 *
 * <p>Delegates to {@link Entitlements#forTier} so all limits live in a
 * single canonical record. These static methods are kept for backward
 * compatibility — callers that already use them do not need to change.
 *
 * <p>New call sites should prefer {@link Entitlements#forTier} directly.
 */
public final class SubscriptionLimits {

    private SubscriptionLimits() {}

    /**
     * How many Mochi tutors a user on the given tier may create.
     *
     * <p>FREE users are still level-gated per {@link LevelRewards#freeTutorCap(int)}
     * so that L5 serves as a meaningful progression milestone.
     * PRO users get a fixed cap of 5.
     * MAX / FAMILY / CENTRE users get -1 (UNLIMITED sentinel).
     *
     * <p>Delegates to {@link Entitlements#forTier(SubscriptionTier, int)}.
     */
    public static int mochiCap(SubscriptionTier tier, int userLevel) {
        int cap = Entitlements.forTier(tier, userLevel).maxMochis();
        // Legacy callers expect Integer.MAX_VALUE for "unlimited", not -1.
        return Entitlements.isUnlimited(cap) ? Integer.MAX_VALUE : cap;
    }

    /**
     * How many children/students a parent/centre account may link.
     *
     * <p>Delegates to {@link Entitlements#forTier(SubscriptionTier)}.
     *
     * @return 0 for plans that do not support linked child accounts (FREE/PRO/MAX).
     */
    public static int familyChildCap(SubscriptionTier tier) {
        int cap = Entitlements.forTier(tier).maxStudents();
        // FREE/PRO/MAX have maxStudents=1 in Entitlements (meaning the payer
        // themselves), but legacy callers of familyChildCap expect 0 for
        // tiers that don't support linking children.
        return switch (tier) {
            case FAMILY  -> cap;
            case CENTRE  -> cap;
            default      -> 0;
        };
    }
}
