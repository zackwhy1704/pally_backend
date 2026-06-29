package com.pally.domain.challenge;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Domain port for class-challenge persistence. The JPA adapter lives in
 * {@code infrastructure/persistence/challenge} and maps JPA ↔ domain.
 */
public interface ChallengeRepository {

    Challenge save(Challenge challenge);

    Optional<Challenge> findById(String id);

    List<Challenge> findByClassIdOrderByCreatedAtDesc(String classId);

    /** Challenges whose reveal time has passed but the push hasn't been sent. */
    List<Challenge> findDueForReveal(Instant now);

    void deleteById(String id);
}
