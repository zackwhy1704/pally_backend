package com.pally.domain.shop;

import com.pally.infrastructure.persistence.powerup.UserPowerupJpaEntity;
import com.pally.infrastructure.persistence.powerup.UserPowerupJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Powerup shop + inventory. Three types:
 * <ul>
 *   <li>HINT_TOKEN (50⭐) — reveal one wrong option in a quiz</li>
 *   <li>DOUBLE_XP (75⭐)  — one-shot booster consumed before next quiz</li>
 *   <li>BONUS_QUIZ (100⭐) — unlocks one full-XP practice quiz today
 *       (bypasses XpService daily decay for that quiz)</li>
 * </ul>
 *
 * <p>Buy = atomic stars-spend + powerup-count upsert. Consume = atomic
 * decrement guarded by {@code count > 0}. Both paths return 400 on
 * insufficient stars / no tokens with a kid-friendly message.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PowerupService {

    public enum Type {
        HINT_TOKEN(50),
        DOUBLE_XP(75),
        BONUS_QUIZ(100);

        public final int starCost;

        Type(int starCost) {
            this.starCost = starCost;
        }

        public static Type parse(String raw) {
            if (raw == null) {
                throw new BusinessException("Unknown powerup type", 400);
            }
            try {
                return Type.valueOf(raw.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(
                        "Unknown powerup type: " + raw, 400);
            }
        }
    }

    private final UserJpaRepository userRepo;
    private final UserPowerupJpaRepository powerupRepo;

    @Transactional
    public Map<String, Object> buy(String userId, Type type) {
        int spent = userRepo.spendStars(userId, type.starCost);
        if (spent == 0) {
            throw new BusinessException(
                    "Not enough stars (need " + type.starCost + ")", 400);
        }
        powerupRepo.upsertCount(userId, type.name(), 1);
        int newStars = userRepo.findById(userId).map(u -> u.getStars()).orElse(0);
        int newCount = currentCount(userId, type);
        log.info("[Powerup] user={} bought {} (cost={}) → count={} stars={}",
                userId, type, type.starCost, newCount, newStars);
        return Map.of(
                "type", type.name(),
                "count", newCount,
                "newStarBalance", newStars);
    }

    @Transactional
    public Map<String, Object> consume(String userId, Type type) {
        int rows = powerupRepo.consume(userId, type.name());
        if (rows == 0) {
            throw new BusinessException(
                    "You have no " + label(type) + " left", 400);
        }
        int remaining = currentCount(userId, type);
        log.info("[Powerup] user={} consumed {} → remaining={}",
                userId, type, remaining);
        return Map.of("type", type.name(), "count", remaining);
    }

    @Transactional(readOnly = true)
    public Map<String, Integer> inventory(String userId) {
        Map<String, Integer> result = new HashMap<>();
        for (Type t : Type.values()) {
            result.put(t.name(), 0);
        }
        for (UserPowerupJpaEntity row : powerupRepo.findById_UserId(userId)) {
            result.put(row.getId().getType(), row.getCount());
        }
        return result;
    }

    private int currentCount(String userId, Type type) {
        return powerupRepo
                .findById(new UserPowerupJpaEntity.Id(userId, type.name()))
                .map(UserPowerupJpaEntity::getCount)
                .orElse(0);
    }

    private static String label(Type type) {
        return switch (type) {
            case HINT_TOKEN -> "hint tokens";
            case DOUBLE_XP -> "double-XP boosters";
            case BONUS_QUIZ -> "bonus quizzes";
        };
    }

    /// Catalog for the shop screen so the client doesn't hardcode prices.
    public Map<String, Object> catalog() {
        Map<String, Object> out = new HashMap<>();
        for (Type t : Type.values()) {
            out.put(t.name(), Map.of("cost", t.starCost, "label", label(t)));
        }
        return Map.of("powerups", out);
    }
}
