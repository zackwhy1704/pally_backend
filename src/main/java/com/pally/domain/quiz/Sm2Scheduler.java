package com.pally.domain.quiz;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

public final class Sm2Scheduler {

    private Sm2Scheduler() {}

    public static FlashCard applyRating(FlashCard card, CardRating rating) {
        double ef = card.easeFactor();
        int reps = card.repetitions();
        int interval = card.intervalDays();

        int q = switch (rating) {
            case HARD -> 2;
            case OKAY -> 4;
            case EASY -> 5;
        };

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
