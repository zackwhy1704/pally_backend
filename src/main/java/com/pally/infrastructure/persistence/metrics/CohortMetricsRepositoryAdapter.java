package com.pally.infrastructure.persistence.metrics;

import com.pally.domain.metrics.CohortMetricsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin pass-through adapter: the SQL (and its Asia/Singapore day bucketing) lives in
 * {@link CohortMetricsJpaRepository}; the honesty rules (empty denominator → null,
 * dormant avatars excluded from denominators) stay in the domain service where they
 * belong. JPA never leaves this package.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortMetricsRepositoryAdapter implements CohortMetricsRepository {

    private final CohortMetricsJpaRepository jpa;

    @Override public int countAllAvatars(String l, String s) { return jpa.countAllAvatars(l, s); }
    @Override public int countActiveAvatars(String l, String s) { return jpa.countActiveAvatars(l, s); }
    @Override public int countDormantAvatars(String l, String s) { return jpa.countDormantAvatars(l, s); }
    @Override public int countReturnedOnLaterDay(String l, String s) { return jpa.countReturnedOnLaterDay(l, s); }
    @Override public int countReviewedCards(String l, String s) { return jpa.countReviewedCards(l, s); }
    @Override public int countRepeatReviewedCards(String l, String s) { return jpa.countRepeatReviewedCards(l, s); }
    @Override public int countRepeatRecallAttempts(String l, String s) { return jpa.countRepeatRecallAttempts(l, s); }
    @Override public int countRepeatRecallCorrect(String l, String s) { return jpa.countRepeatRecallCorrect(l, s); }
    @Override public Double medianDaysToSecondSession(String l, String s) { return jpa.medianDaysToSecondSession(l, s); }
    @Override public int countAvatarsWithSecondDay(String l, String s) { return jpa.countAvatarsWithSecondDay(l, s); }
}
