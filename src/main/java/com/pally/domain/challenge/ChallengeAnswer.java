package com.pally.domain.challenge;

import java.time.Instant;

/** Domain model for a student's locked challenge answer. */
public record ChallengeAnswer(
        String id,
        String challengeId,
        String userId,
        String answer,
        Instant createdAt) {
}
