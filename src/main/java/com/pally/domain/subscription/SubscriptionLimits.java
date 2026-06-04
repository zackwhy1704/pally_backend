package com.pally.domain.subscription;

import com.pally.domain.progress.LevelRewards;

/**
 * Centralised tier-aware limits.
 *
 * <p>Every limit in the system should be read from here, not hardcoded at the
 * call site, so a tier change only requires editing one class.
 */
public final class SubscriptionLimits {

    private SubscriptionLimits() {}

    /**
     * How many Mochi tutors a user on the given tier may create.
     *
     * <p>FREE users are still level-gated per {@link LevelRewards#freeTutorCap(int)}
     * so that L5 serves as a meaningful progression milestone.
     * PRO users get a fixed cap of 5.
     * MAX / FAMILY / CENTRE users are unlimited.
     *
     * @return {@link Integer#MAX_VALUE} to signal "unlimited" — callers must
     *         treat any value ≥ existing count as "allowed".
     */
    public static int mochiCap(SubscriptionTier tier, int userLevel) {
        return switch (tier) {
            case FREE    -> LevelRewards.freeTutorCap(userLevel);
            case PRO     -> 5;
            case MAX, FAMILY, CENTRE -> Integer.MAX_VALUE;
        };
    }

    /**
     * How many children/students a parent/centre account may link.
     *
     * @return 0 for plans that do not support linked child accounts.
     */
    public static int familyChildCap(SubscriptionTier tier) {
        return switch (tier) {
            case FAMILY  -> 4;
            case CENTRE  -> 15;
            default      -> 0;
        };
    }
}
