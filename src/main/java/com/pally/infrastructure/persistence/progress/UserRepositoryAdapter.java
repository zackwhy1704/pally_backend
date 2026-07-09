package com.pally.infrastructure.persistence.progress;

import com.pally.domain.account.AccountType;
import com.pally.domain.progress.LevelRewards;
import com.pally.domain.progress.ProgressSummary;
import com.pally.domain.progress.StreakService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserRepositoryAdapter implements UserRepository {

    private final UserJpaRepository jpa;

    @Override
    public Optional<User> findById(String userId) {
        return jpa.findById(userId).map(UserJpaEntity::toUserDomain);
    }

    @Override
    @Transactional
    public User save(User user) {
        UserJpaEntity entity = jpa.findById(user.getId())
                .orElseThrow(() -> new BusinessException("User not found: " + user.getId(), 404));
        applyDomainToEntity(user, entity);
        return jpa.save(entity).toUserDomain();
    }

    @Override
    @Transactional
    public void deleteById(String userId) {
        jpa.deleteById(userId);
    }

    @Override
    @Transactional
    public void markDeletionPending(String userId, Instant requestedAt) {
        UserJpaEntity e = jpa.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found: " + userId, 404));
        // Targeted update (NOT a full applyDomainToEntity) so the epoch bump can never
        // be clobbered by a stale domain snapshot — the domain User doesn't carry epoch.
        e.setAccountStatus(com.pally.domain.consent.ConsentGuard.STATUS_DELETION_PENDING);
        e.setDeletionRequestedAt(requestedAt);
        e.setSessionEpoch(e.getSessionEpoch() + 1); // kills every outstanding token
        jpa.save(e);
    }

    @Override
    public int countByParentId(String parentId) {
        return jpa.countByParentId(parentId);
    }

    @Override
    public List<User> findByParentId(String parentId) {
        return jpa.findByParentId(parentId).stream()
                .map(UserJpaEntity::toUserDomain)
                .toList();
    }

    @Override
    public List<User> findByAccountType(AccountType accountType) {
        return jpa.findByAccountType(accountType).stream()
                .map(UserJpaEntity::toUserDomain)
                .toList();
    }

    @Override
    public Optional<User> findByReferralCode(String referralCode) {
        return jpa.findByReferralCode(referralCode).map(UserJpaEntity::toUserDomain);
    }

    @Override
    public void ensureUserExists(String userId) {
        if (!jpa.existsById(userId)) {
            jpa.save(UserJpaEntity.newUser(userId));
        }
    }

    @Override
    @Transactional
    public int spendStars(String userId, int cost) {
        return jpa.spendStars(userId, cost);
    }

    @Override
    @Transactional
    public int buyStreakFreeze(String userId, int cost, int cap) {
        return jpa.buyStreakFreeze(userId, cost, cap);
    }

    @Override
    @Transactional
    public int earnStreakFreeze(String userId, int cap) {
        return jpa.earnStreakFreeze(userId, cap);
    }

    @Override
    @Transactional
    public int consumeStreakFreeze(String userId) {
        return jpa.consumeStreakFreeze(userId);
    }

    @Override
    @Transactional
    public UserRepository.XpResult addXpAndStars(String userId, int xpDelta, int starsDelta) {
        if (xpDelta == 0 && starsDelta == 0) {
            int xp = jpa.findById(userId).map(UserJpaEntity::getXp).orElse(0);
            int lvl = ProgressSummary.computeLevel(xp);
            return UserRepository.XpResult.unchanged(xp, lvl);
        }

        var before = jpa.findById(userId).orElse(null);
        if (before == null) {
            log.warn("[XP] addXpAndStars: user {} not found", userId);
            return UserRepository.XpResult.unchanged(0, 1);
        }
        int oldXp = before.getXp();
        int oldLevel = ProgressSummary.computeLevel(oldXp);

        int updated = jpa.creditXpAndStars(userId, xpDelta, starsDelta);
        if (updated == 0) {
            log.warn("[XP] addXpAndStars: 0 rows updated for {}", userId);
            return UserRepository.XpResult.unchanged(oldXp, oldLevel);
        }

        int newXp = oldXp + xpDelta;
        int newLevel = ProgressSummary.computeLevel(newXp);
        if (newLevel != oldLevel) {
            jpa.updateLevel(userId, newLevel);
        }

        String unlockedLabel = null;
        if (newLevel > oldLevel) {
            for (int lvl = oldLevel + 1; lvl <= newLevel; lvl++) {
                var reward = LevelRewards.atLevel(lvl);
                if (reward != null) unlockedLabel = reward.label();
                if (lvl == 20) {
                    int newCap = StreakService.effectiveFreezeCap(20);
                    int granted = jpa.grantFreezesUpTo(
                            userId, LevelRewards.L20_FREEZE_STACK, newCap);
                    log.info("[XP] user={} crossed L20 → granted up to {} "
                            + "freezes (cap {}) rows={}", userId,
                            LevelRewards.L20_FREEZE_STACK, newCap, granted);
                }
            }
        }

        log.info("[XP] user={} +{}xp +{}stars → xp={} level={}→{}{}",
                userId, xpDelta, starsDelta, newXp,
                oldLevel, newLevel,
                unlockedLabel == null ? "" : " unlock=" + unlockedLabel);
        return new UserRepository.XpResult(
                newXp, oldLevel, newLevel, newLevel > oldLevel, unlockedLabel);
    }

    @Override
    public long countByCentreId(String centreId) {
        return jpa.countByCentreId(centreId);
    }

    @Override
    public List<User> findAllByIds(Collection<String> ids) {
        return jpa.findAllById(ids).stream()
                .map(UserJpaEntity::toUserDomain)
                .toList();
    }

    // ── PIN management ────────────────────────────────────────────────────

    @Override
    public Optional<String> getParentPinHash(String userId) {
        return jpa.findById(userId)
                .map(UserJpaEntity::getParentPinHash)
                .filter(h -> h != null && !h.isBlank());
    }

    @Override
    @Transactional
    public void setParentPinHash(String userId, String bcryptHash) {
        UserJpaEntity e = jpa.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        e.setParentPinHash(bcryptHash);
        jpa.save(e);
    }

    @Override
    public Optional<String> getPasswordHash(String userId) {
        return jpa.findById(userId)
                .map(UserJpaEntity::getPasswordHash)
                .filter(h -> h != null && !h.isBlank());
    }

    // ── Screen-time ───────────────────────────────────────────────────────

    @Override
    @Transactional
    public void setScreenTime(String userId, boolean enabled, int minutes) {
        UserJpaEntity e = jpa.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        e.setScreenTimeEnabled(enabled);
        e.setScreenTimeMinutes(minutes);
        jpa.save(e);
    }

    // ── FCM token ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void setFcmToken(String userId, String token) {
        UserJpaEntity e = jpa.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        e.setFcmToken(token);
        jpa.save(e);
    }

    // ── Link-code (family pairing) ────────────────────────────────────────

    @Override
    public Optional<User> findByLinkCode(String code) {
        return jpa.findByLinkCode(code).map(UserJpaEntity::toUserDomain);
    }

    @Override
    @Transactional
    public void setLinkCode(String userId, String code, Instant expiresAt) {
        UserJpaEntity e = jpa.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        e.setLinkCode(code);
        e.setLinkCodeExpiresAt(expiresAt);
        jpa.save(e);
    }

    @Override
    @Transactional
    public void clearLinkCode(String userId) {
        UserJpaEntity e = jpa.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        e.setLinkCode(null);
        e.setLinkCodeExpiresAt(null);
        jpa.save(e);
    }

    private void applyDomainToEntity(User user, UserJpaEntity entity) {
        entity.setDisplayName(user.getDisplayName());
        entity.setChildName(user.getChildName());
        entity.setParentId(user.getParentId());
        entity.setAccountType(user.getAccountType());
        entity.setXp(user.getXp());
        entity.setLevel(user.getLevel());
        entity.setStreakDays(user.getStreakDays());
        entity.setLongestStreak(user.getLongestStreak());
        entity.setLastActiveDate(user.getLastActiveDate());
        entity.setStreakMilestonesReached(user.getStreakMilestonesReached());
        entity.setPremium(user.isPremium());
        entity.setReferralCode(user.getReferralCode());
        entity.setEmailVerified(user.isEmailVerified());
        entity.setAccountStatus(user.getAccountStatus());
        entity.setBirthYear(user.getBirthYear());
        entity.setTrialStatus(user.getTrialStatus());
        entity.setTrialStartedAt(user.getTrialStartedAt());
        entity.setTrialEndsAt(user.getTrialEndsAt());
        entity.setLastSlotChangeAt(user.getLastSlotChangeAt());
    }
}
