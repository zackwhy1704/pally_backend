package com.pally.infrastructure.persistence.progress;

import com.pally.domain.progress.LevelRewards;
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

    /// Atomic XP + stars credit via a single UPDATE — closes the D1
    /// lost-update race the audit flagged. We round-trip a SELECT for the
    /// pre-image (to detect level crossings + name the unlock) and a
    /// recompute after the UPDATE for the post-image. Concurrent credits
    /// won't lose increments because the increment happens in-DB.
    ///
    /// <p>FUNCTIONAL level rewards (L5 tutor slot, L20 freeze cap) are
    /// read at USE time from {@link LevelRewards} / {@code StreakService},
    /// so this method no longer mutates {@code streak_freezes} on level-up.
    /// That was the source of bug-class drift between "what level unlocks"
    /// and "what state the user actually has."
    @Override
    @Transactional
    public UserRepository.XpResult addXpAndStars(
            String userId, int xpDelta, int starsDelta) {
        if (xpDelta == 0 && starsDelta == 0) {
            int xp = jpa.findById(userId).map(UserJpaEntity::getXp).orElse(0);
            int lvl = ProgressSummary.computeLevel(xp);
            return UserRepository.XpResult.unchanged(xp, lvl);
        }

        // Pre-image — we need the OLD xp to compute crossed levels.
        var before = jpa.findById(userId).orElse(null);
        if (before == null) {
            log.warn("[XP] addXpAndStars: user {} not found", userId);
            return UserRepository.XpResult.unchanged(0, 1);
        }
        int oldXp = before.getXp();
        int oldLevel = ProgressSummary.computeLevel(oldXp);

        // Atomic increment. Returns row count; 0 means the user vanished
        // between the SELECT above and this UPDATE — vanishingly rare but
        // we degrade gracefully.
        int updated = jpa.creditXpAndStars(userId, xpDelta, starsDelta);
        if (updated == 0) {
            log.warn("[XP] addXpAndStars: 0 rows updated for {}", userId);
            return UserRepository.XpResult.unchanged(oldXp, oldLevel);
        }

        int newXp = oldXp + xpDelta;
        int newLevel = ProgressSummary.computeLevel(newXp);
        // Persist the computed level only when it actually moved — avoids
        // an extra write on every credit.
        if (newLevel != oldLevel) {
            jpa.updateLevel(userId, newLevel);
        }

        // Resolve the highest unlock crossed this round, purely informational.
        String unlockedLabel = null;
        if (newLevel > oldLevel) {
            for (int lvl = oldLevel + 1; lvl <= newLevel; lvl++) {
                var reward = LevelRewards.atLevel(lvl);
                if (reward != null) unlockedLabel = reward.label();
            }
        }

        log.info("[XP] user={} +{}xp +{}stars → xp={} level={}→{}{}",
                userId, xpDelta, starsDelta, newXp,
                oldLevel, newLevel,
                unlockedLabel == null ? "" : " unlock=" + unlockedLabel);
        return new UserRepository.XpResult(
                newXp, oldLevel, newLevel, newLevel > oldLevel, unlockedLabel);
    }
}
