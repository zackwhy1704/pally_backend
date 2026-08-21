package com.pally.domain.quiz;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Sm2Scheduler {

    private Sm2Scheduler() {}

    /**
     * The SM-2 quality value for a rating. Exposed (rather than inlined in
     * {@link #applyRating}) so review history records the SAME q the scheduler
     * actually acted on — a second copy of this mapping could drift and would make
     * the history a record of something that never happened.
     *
     * <p>q &lt; 3 is a lapse: it resets repetitions to 0 and the interval to 1 day.
     * HARD (q=2) is therefore a RESET, not a slow-down — which is why a card rated
     * HARD is indistinguishable from a never-reviewed card in the flashcards table
     * alone, and why this history exists.
     */
    public static int qualityOf(CardRating rating) {
        return switch (rating) {
            case HARD -> 2;
            case OKAY -> 4;
            case EASY -> 5;
        };
    }

    public static FlashCard applyRating(FlashCard card, CardRating rating) {
        double ef = card.easeFactor();
        int reps = card.repetitions();
        int interval = card.intervalDays();

        int q = qualityOf(rating);

        ef = Math.max(1.3, ef + 0.1 - (5 - q) * (0.08 + (5 - q) * 0.02));

        if (q < 3) {
            reps = 0;
            interval = 1;
        } else if (reps == 0) {
            interval = 1;
            reps = 1;
        } else if (reps == 1) {
            interval = 6;
            reps = 2;
        } else {
            interval = (int) Math.round(interval * ef);
            reps++;
        }

        Instant nextReview = Instant.now().plus(interval, ChronoUnit.DAYS);
        return card.withRating(rating, nextReview, reps, ef, interval);
    }
}
