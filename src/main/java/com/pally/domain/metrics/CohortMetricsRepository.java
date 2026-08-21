package com.pally.domain.metrics;

/**
 * Domain port for cohort metric aggregates. The JPA adapter lives in
 * {@code infrastructure/persistence/metrics}.
 *
 * <p>Exists because {@code DomainLayeringGuardTest} correctly rejected the domain
 * service importing the JPA repository directly. The guard's allow-list only ever
 * shrinks — a new violation gets a port, not an exemption.
 *
 * <p>Read-only by intent: every method is a count or an aggregate. There is no save.
 * All day boundaries are Asia/Singapore, applied in the adapter's SQL.
 */
public interface CohortMetricsRepository {

    /** Every avatar in the cohort — the activation denominator. */
    int countAllAvatars(String level, String subject);

    /** Avatars with at least one recorded learning action — the rate denominator. */
    int countActiveAvatars(String level, String subject);

    /** Avatars holding content but with ZERO activity. Reported, never counted. */
    int countDormantAvatars(String level, String subject);

    /** Active avatars whose activity spans more than one Asia/Singapore calendar day. */
    int countReturnedOnLaterDay(String level, String subject);

    /** Distinct cards with any review row. */
    int countReviewedCards(String level, String subject);

    /** Cards reviewed more than once. */
    int countRepeatReviewedCards(String level, String subject);

    /** Reviews that found the card already in a successful streak (genuine repeats). */
    int countRepeatRecallAttempts(String level, String subject);

    /** Genuine repeats recalled successfully (SM-2 quality >= 3). */
    int countRepeatRecallCorrect(String level, String subject);

    /** Median days from first to second active day; null when nobody has a second. */
    Double medianDaysToSecondSession(String level, String subject);

    /** How many avatars have a second active day — the median's n. */
    int countAvatarsWithSecondDay(String level, String subject);
}
