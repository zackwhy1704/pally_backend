package com.pally.domain.progress;

import com.pally.infrastructure.persistence.badge.UserBadgeJpaEntity;
import com.pally.infrastructure.persistence.badge.UserBadgeJpaRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BadgeService {

    public enum BadgeType {
        FIRST_CHAT("💬"),
        FIRST_QUIZ("📝"),
        FIRST_UPLOAD("📚"),
        STREAK_3("🔥"),
        STREAK_7("🔥"),
        STREAK_30("🏆"),
        LEVEL_5("⭐"),
        LEVEL_10("🌟"),
        PERFECT_QUIZ("💯"),
        PHOTOS_10("📸");

        public final String emoji;

        BadgeType(String emoji) {
            this.emoji = emoji;
        }
    }

    private final UserBadgeJpaRepository repo;
    private final UserRepository userRepo;

    /// Returns the user's badges as emoji list, sorted by earn time.
    public List<String> getBadges(String userId) {
        return repo.findByUserId(userId).stream()
                .map(b -> safeBadge(b.getBadgeType()))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /// Awards any milestone badges the user newly qualifies for. Idempotent —
    /// re-running won't grant duplicates (unique constraint protects).
    @Transactional
    public List<String> checkAndGrantMilestones(String userId) {
        UserStats stats = userRepo.findById(userId).orElse(null);
        if (stats == null) return List.of();

        List<String> newlyEarned = new ArrayList<>();
        if (stats.streakDays() >= 3) addIfNew(userId, BadgeType.STREAK_3, newlyEarned);
        if (stats.streakDays() >= 7) addIfNew(userId, BadgeType.STREAK_7, newlyEarned);
        if (stats.streakDays() >= 30) addIfNew(userId, BadgeType.STREAK_30, newlyEarned);
        if (stats.level() >= 5) addIfNew(userId, BadgeType.LEVEL_5, newlyEarned);
        if (stats.level() >= 10) addIfNew(userId, BadgeType.LEVEL_10, newlyEarned);
        return newlyEarned;
    }

    @Transactional
    public List<String> grantFirstAction(String userId, BadgeType type) {
        List<String> earned = new ArrayList<>();
        addIfNew(userId, type, earned);
        return earned;
    }

    @Transactional
    public List<String> grantPerfectQuiz(String userId, int correct, int total) {
        if (total > 0 && correct == total) {
            List<String> earned = new ArrayList<>();
            addIfNew(userId, BadgeType.PERFECT_QUIZ, earned);
            return earned;
        }
        return List.of();
    }

    private void addIfNew(String userId, BadgeType type, List<String> out) {
        if (repo.existsByUserIdAndBadgeType(userId, type.name())) return;
        try {
            UserBadgeJpaEntity e = new UserBadgeJpaEntity();
            e.setId(IdGenerator.newId());
            e.setUserId(userId);
            e.setBadgeType(type.name());
            e.setEarnedAt(Instant.now());
            repo.save(e);
            out.add(type.emoji);
            log.info("[Badge] Granted {} to user {}", type.name(), userId);
        } catch (Exception ex) {
            // unique-constraint race or other DB error — non-critical
            log.debug("[Badge] grant {} skipped: {}", type.name(), ex.getMessage());
        }
    }

    private String safeBadge(String typeName) {
        try {
            return BadgeType.valueOf(typeName).emoji;
        } catch (Exception ignored) {
            return "";
        }
    }
}
