package com.pally.domain.subscription;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Config-driven daily chat quotas per tier.
 *
 * <p><b>Why these are injected rather than {@code public static final int}.</b>
 * They used to be compile-time constants on {@code ChatRateLimiter}, read
 * statically from {@code UsageController}. A {@code static final int} is INLINED
 * into every calling class at compile time, so overriding it via configuration
 * would change the limiter while {@code /usage} kept reporting the old number —
 * the "changed the config, nothing happened" trap, except worse, because the two
 * surfaces would disagree about the same user's quota.
 *
 * <p>Tunable from a Railway variable without an app update:
 * {@code CHAT_QUOTA_FREE}, {@code CHAT_QUOTA_PRO}. The live values are printed at
 * startup by {@code ChatQuotaStartupLogger}, so an override is verifiable from the
 * boot log rather than assumed — the same treatment as {@code GradingWeights}.
 *
 * <p>{@link #UNLIMITED} is a sentinel, not a large number: callers skip the
 * counter entirely rather than comparing against a big int. A "very large limit"
 * would still allocate and increment per-user counters forever for users who can
 * never hit it.
 */
@Component
@ConfigurationProperties(prefix = "chat.quota")
public class ChatQuotaProperties {

    /** Sentinel meaning "no cap" — callers must skip counting, not compare. */
    public static final int UNLIMITED = Integer.MAX_VALUE;

    /**
     * Free-tier daily chat cap. 20/day: enough to use the tutor meaningfully on
     * real homework every day, with a visible ceiling to upgrade against.
     * Deliberately UNCHANGED at launch — there are zero paying users to learn
     * from, so moving it would be guessing.
     */
    private int free = 20;

    /**
     * Pro-tier daily cap. UNLIMITED at launch: against a visible free cap,
     * "unlimited" is legible in a way "100/day" is not — a student cannot tell
     * where a numeric ceiling sits until they hit it.
     */
    private int pro = UNLIMITED;

    public int getFree() { return free; }
    public void setFree(int free) { this.free = free; }

    public int getPro() { return pro; }
    public void setPro(int pro) { this.pro = pro; }

    /**
     * Daily cap for a tier. {@link #UNLIMITED} means no cap.
     *
     * <p>MAX and FAMILY are not sold at launch (the RevenueCat offering exposes
     * PRO only) but remain recognised so they can be enabled server-side later
     * without an app update.
     */
    public int dailyLimitFor(SubscriptionTier tier) {
        return switch (tier) {
            case FREE            -> free;
            case PRO             -> pro;
            case MAX, FAMILY     -> UNLIMITED;
        };
    }
}
