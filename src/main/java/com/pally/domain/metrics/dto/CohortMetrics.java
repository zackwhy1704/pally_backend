package com.pally.domain.metrics.dto;

/**
 * Cohort learning-outcome metrics — the numbers that are simultaneously proof the
 * product works and the centre-facing pitch. Read-model only; nothing here is
 * persisted and nothing is written.
 *
 * <p><b>Denominator honesty.</b> {@code activeAvatars} is the denominator for every
 * rate. {@code dormantAvatars} (holding content but with zero recorded activity —
 * typically auto-generated at compile time for an account that never engaged) is
 * reported ALONGSIDE, never folded in and never silently dropped. Including them
 * understates every rate; hiding them overstates the population. Both numbers ship.
 *
 * <p><b>Never a misleading zero.</b> A rate whose denominator is empty is
 * {@code null} with {@code status = INSUFFICIENT_DATA} and its denominator exposed —
 * never {@code 0.0}. "0% of students retain what they learn" and "no student has
 * reached a second review yet" are opposite claims, and in a sales conversation the
 * first one is a lie the data does not support.
 */
public record CohortMetrics(
        /** Cohort filter echoed back; null = all avatars. */
        String levelFilter,
        String subjectFilter,

        /** Avatars with at least one recorded learning action — the rate denominator. */
        int activeAvatars,
        /** Avatars holding content but with ZERO activity. Reported, never counted. */
        int dormantAvatars,

        /** % of signed-up avatars that completed a first learning action. */
        Rate activation,
        /** % active on a later Asia/Singapore calendar day than their first action. */
        Rate returnRate,
        /** % of reviewed cards reviewed more than once. */
        Rate repeatReview,
        /** Of cards reviewed 2+ times, % correct on the LATER review. The number the
         *  mastery claim rests on. */
        Rate verifiedRetention,

        /** Median days between first and second active day, with its n. */
        MedianDays medianDaysFirstToSecond
) {

    /** Why a rate has no value. */
    public enum Status {
        /** Computed from a non-empty denominator. */
        OK,
        /** Denominator is empty — the behaviour has not happened yet. NOT zero. */
        INSUFFICIENT_DATA
    }

    /**
     * @param value       0–100, or null when {@code status == INSUFFICIENT_DATA}.
     * @param numerator   matching rows.
     * @param denominator population the rate is over — exposed so a reader can see
     *                    WHY a null is null rather than guessing.
     */
    public record Rate(Double value, int numerator, int denominator, Status status) {

        /** Builds a rate, collapsing an empty denominator to INSUFFICIENT_DATA. */
        public static Rate of(int numerator, int denominator) {
            if (denominator <= 0) {
                return new Rate(null, numerator, denominator, Status.INSUFFICIENT_DATA);
            }
            double pct = (double) numerator / denominator * 100.0;
            return new Rate(Math.round(pct * 100.0) / 100.0, numerator, denominator, Status.OK);
        }
    }

    /**
     * @param days     median, or null when nobody has a second day yet.
     * @param n        how many avatars the median is over. A real n of 2 is reported
     *                 WITH the caveat rather than hidden — a suppressed metric and a
     *                 low-confidence metric are different things.
     * @param lowN     true when n is too small to generalise from.
     */
    public record MedianDays(Double days, int n, boolean lowN, Status status) {

        /** Below this, the median is real but must not be generalised from. */
        public static final int LOW_N_THRESHOLD = 10;

        public static MedianDays of(Double days, int n) {
            if (n <= 0 || days == null) {
                return new MedianDays(null, n, true, Status.INSUFFICIENT_DATA);
            }
            return new MedianDays(days, n, n < LOW_N_THRESHOLD, Status.OK);
        }
    }
}
