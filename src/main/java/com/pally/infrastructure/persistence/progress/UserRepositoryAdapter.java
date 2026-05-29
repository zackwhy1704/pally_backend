package com.pally.infrastructure.persistence.progress;

import com.pally.domain.progress.LevelRewards;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.StreakService;
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
    public UserRepository.XpResult addXpAndStars(
            String userId, int xpDelta, int starsDelta) {
        var entity = jpa.findById(userId).orElse(null);
        if (entity == null) {
            log.warn("[XP] addXpAndStars: user {} not found", userId);
            return UserRepository.XpResult.unchanged(0, 1);
        }
        int oldLevel = ProgressSummary.computeLevel(entity.getXp());
        int newXp = entity.getXp() + xpDelta;
        int newStars = entity.getStars() + starsDelta;
        int newLevel = ProgressSummary.computeLevel(newXp);
        entity.setXp(newXp);
        entity.setStars(newStars);
        entity.setLevel(newLevel);

        // Apply functional level-up rewards. We only walk crossed levels
        // (oldLevel+1..newLevel) so re-saving without a level-up is a no-op.
        String unlockedLabel = null;
        if (newLevel > oldLevel) {
            for (int lvl = oldLevel + 1; lvl <= newLevel; lvl++) {
                var reward = LevelRewards.atLevel(lvl);
                if (reward == null) continue;
                unlockedLabel = reward.label();
                if (reward.kind() == LevelRewards.Reward.Kind.FUNCTIONAL
                        && "+1 streak freeze".equals(reward.label())
                        && entity.getStreakFreezes() < StreakService.FREEZE_CAP) {
                    entity.setStreakFreezes(entity.getStreakFreezes() + 1);
                    log.info("[XP] user={} L{} unlocked +1 freeze (now={})",
                            userId, lvl, entity.getStreakFreezes());
                }
            }
        }
        jpa.save(entity);
        log.info("[XP] user={} +{}xp +{}stars → xp={} stars={} level={}→{}{}",
                userId, xpDelta, starsDelta, newXp, newStars,
                oldLevel, newLevel,
                unlockedLabel == null ? "" : " unlock=" + unlockedLabel);
        return new UserRepository.XpResult(
                newXp, oldLevel, newLevel, newLevel > oldLevel, unlockedLabel);
    }
}
