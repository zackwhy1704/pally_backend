package com.pally.infrastructure.persistence.activity;

import com.pally.domain.parent.ActivityLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ActivityLogRepositoryAdapter implements ActivityLogRepository {

    private final ActivityLogJpaRepository jpa;

    @Override
    public int countSince(String userId, Instant since) {
        Integer v = jpa.countSince(userId, since);
        return v == null ? 0 : v;
    }

    @Override
    public int sumXpSince(String userId, Instant since) {
        Integer v = jpa.sumXpSince(userId, since);
        return v == null ? 0 : v;
    }

    @Override
    public int sumMinutesBetween(String userId, Instant from, Instant to) {
        Integer v = jpa.sumMinutesBetween(userId, from, to);
        return v == null ? 0 : v;
    }

    @Override
    public int countBetween(String userId, Instant from, Instant to) {
        Integer v = jpa.countBetween(userId, from, to);
        return v == null ? 0 : v;
    }

    @Override
    public int sumXpBetween(String userId, Instant from, Instant to) {
        Integer v = jpa.sumXpBetween(userId, from, to);
        return v == null ? 0 : v;
    }
}
