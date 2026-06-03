package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.progress.LevelRewards;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Activates or deactivates an avatar within the free-tier slot system.
 *
 * <p>Rules:
 * <ul>
 *   <li>Premium users: unlimited — activate/deactivate freely, no cooldown.
 *   <li>Free users: up to {@link LevelRewards#freeTutorCap} ACTIVE avatars.
 *   <li>Activating when already at cap → must deactivate one first
 *       (or the client should deactivate-then-activate in sequence).
 *   <li>24-hour cooldown between slot changes for free users.
 *       Deactivating does NOT consume the cooldown — only activating does.
 *   <li>Deactivating the LAST active avatar is prevented (must always have ≥1).
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SetAvatarActiveUseCase {

    private static final Logger log = LoggerFactory.getLogger(SetAvatarActiveUseCase.class);
    private static final Duration SWAP_COOLDOWN = Duration.ofHours(24);

    private final AvatarRepository avatarRepository;
    private final PremiumService premiumService;
    private final UserJpaRepository userJpaRepository;

    public record Result(boolean isActive, String message, long cooldownSecondsRemaining) {}

    @Transactional
    public Result execute(String avatarId, String userId, boolean activate) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new BusinessException("Avatar not found", 404));

        // No-op if already in the requested state
        if (avatar.isActive() == activate) {
            String msg = activate ? "Already active" : "Already inactive";
            return new Result(activate, msg, 0);
        }

        PremiumService.Entitlement ent = premiumService.resolve(userId);
        boolean isPremium = ent.isPremium();

        if (activate) {
            if (!isPremium) {
                enforceActivationRules(userId, avatar);
            }
        } else {
            // Prevent deactivating the last active avatar
            long activeCount = avatarRepository.findByUserId(userId)
                    .stream().filter(Avatar::isActive).count();
            if (activeCount <= 1) {
                throw new BusinessException(
                        "You must keep at least one Mochi active.", 422);
            }
        }

        avatar.setActive(activate);
        avatarRepository.save(avatar);

        // Record activation time for cooldown tracking (deactivation is free)
        if (activate && !isPremium) {
            userJpaRepository.findById(userId).ifPresent(user -> {
                user.setLastSlotChangeAt(Instant.now());
                userJpaRepository.save(user);
            });
        }

        String msg = activate
                ? avatar.getName() + " is now active!"
                : avatar.getName() + " is now locked.";
        log.info("[Slot] user={} avatar={} activate={} premium={}", userId, avatarId, activate, isPremium);
        return new Result(activate, msg, 0);
    }

    private void enforceActivationRules(String userId, Avatar avatar) {
        List<Avatar> allAvatars = avatarRepository.findByUserId(userId);
        long activeCount = allAvatars.stream().filter(Avatar::isActive).count();

        // Check level-based cap
        int level = userJpaRepository.findById(userId)
                .map(u -> u.getLevel() > 0 ? u.getLevel() : 1)
                .orElse(1);
        int cap = LevelRewards.freeTutorCap(level);
        if (activeCount >= cap) {
            throw new BusinessException(
                    "You've reached your active Mochi limit (" + cap + "). "
                    + "Deactivate another Mochi first, then select this one.", 422);
        }

        // Check 24-hour cooldown
        userJpaRepository.findById(userId).ifPresent(user -> {
            Instant lastChange = user.getLastSlotChangeAt();
            if (lastChange != null) {
                Duration elapsed = Duration.between(lastChange, Instant.now());
                if (elapsed.compareTo(SWAP_COOLDOWN) < 0) {
                    long secondsLeft = SWAP_COOLDOWN.minus(elapsed).getSeconds();
                    long hoursLeft = secondsLeft / 3600;
                    long minsLeft = (secondsLeft % 3600) / 60;
                    throw new BusinessException(
                            "You can swap a Mochi again in " + hoursLeft + "h " + minsLeft + "m. "
                            + "Each free swap takes 24 hours to refresh.", 429);
                }
            }
        });
    }
}
