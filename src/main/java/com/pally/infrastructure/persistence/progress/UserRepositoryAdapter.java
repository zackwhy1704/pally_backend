package com.pally.infrastructure.persistence.progress;

import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpa;

    @Override
    public Optional<UserStats> findById(String userId) {
        return jpa.findById(userId).map(UserJpaEntity::toDomain);
    }

    @Override
    public UserStats save(UserStats stats) {
        return jpa.save(UserJpaEntity.fromDomain(stats)).toDomain();
    }

    @Override
    public void ensureUserExists(String userId) {
        if (!jpa.existsById(userId)) {
            jpa.save(UserJpaEntity.newUser(userId));
        }
    }

    @Override
    @Transactional
    public void addXpAndStars(String userId, int xpDelta, int starsDelta) {
        var entity = jpa.findById(userId).orElse(null);
        if (entity == null) {
            log.warn("[XP] addXpAndStars: user {} not found", userId);
            return;
        }
        int newXp = entity.getXp() + xpDelta;
        int newStars = entity.getStars() + starsDelta;
        int newLevel = ProgressSummary.computeLevel(newXp);
        entity.setXp(newXp);
        entity.setStars(newStars);
        entity.setLevel(newLevel);
        jpa.save(entity);
        log.info("[XP] user={} +{}xp +{}stars → total xp={} stars={} level={}",
                userId, xpDelta, starsDelta, newXp, newStars, newLevel);
    }
}
