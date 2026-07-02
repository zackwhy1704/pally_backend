package com.pally.infrastructure.persistence.weakness;

import com.pally.domain.avatar.Subject;
import com.pally.domain.weakness.WeaknessStateStore;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class WeaknessStateStoreAdapter implements WeaknessStateStore {

    private final WeaknessProfileStateJpaRepository jpa;

    @Override
    public Optional<WeaknessState> find(String userId, Subject subject) {
        return jpa.findByUserIdAndSubject(userId, subject.name())
                .map(e -> new WeaknessState(e.getWeakSlugs(), e.getRecentWins()));
    }

    @Override
    public void upsert(String userId, Subject subject, String weakSlugs, String recentWins) {
        WeaknessProfileStateJpaEntity e = jpa
                .findByUserIdAndSubject(userId, subject.name())
                .orElseGet(() -> {
                    WeaknessProfileStateJpaEntity fresh = new WeaknessProfileStateJpaEntity();
                    fresh.setId(IdGenerator.newId());
                    fresh.setUserId(userId);
                    fresh.setSubject(subject.name());
                    return fresh;
                });
        e.setWeakSlugs(weakSlugs);
        e.setRecentWins(recentWins);
        e.setUpdatedAt(Instant.now());
        jpa.save(e);
    }
}
