package com.pally.domain.subscription;

/**
 * Tier classification derived from the user's active subscription plan.
 *
 * <p>Resolution order: CENTRE > FAMILY > MAX > PRO > FREE.
 * Legacy plans (individual_monthly, admin) are treated as MAX.
 */
public enum SubscriptionTier {
    /** No active subscription — 20 chats/day, 1-2 Mochis (level-gated). */
    FREE,
    /** Plan contains "pro" — 100 chats/day, up to 5 Mochis. */
    PRO,
    /** Plan contains "max" — unlimited chats, unlimited Mochis, Sonnet for complex questions. */
    MAX,
    /** Plan contains "family" — up to 4 children linked, each gets unlimited chats. */
    FAMILY,
    /** Plan contains "centre" — up to 15 students linked, each gets unlimited chats. */
    CENTRE
}
