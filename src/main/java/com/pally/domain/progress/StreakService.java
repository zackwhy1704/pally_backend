package com.pally.domain.progress;

import com.pally.infrastructure.persistence.activity.DailyActivityDayJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Shared streak logic — owns the day-roll, freeze consumption, milestone
 * grants, and earned-freeze top-ups. Called from both login (AuthService)
 * and any XP-crediting activity (via ActivityLogService) so a kid who
 * skips login but does a quiz still keeps their streak.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StreakService {

    public static final int[] MILESTONES = {3, 7, 14, 30, 60, 100, 365};
    /// Base cap. {@link #effectiveFreezeCap(int)} is the real ceiling
    /// callers should consult — L20 raises it to 5 (a level-unlock perk).
    public static final int FREEZE_CAP = 3;
    private static final int FREEZE_CAP_L20 = 5;

    /// Returns the freeze cap for a user at {@code level}. L20+ get +2.
    /// Centralised so the shop, milestone earn, and per-day-roll all
    /// honour the same ceiling — drift here was the original bug.
    public static int effectiveFreezeCap(int level) {
        return level >= 20 ? FREEZE_CAP_L20 : FREEZE_CAP;
    }

    private final UserJpaRepository userRepo;
    private final DailyActivityDayJpaRepository dayRepo;
    private final BadgeService badgeService;

    public record StreakUpdateResult(
            int streakDays,
            int longestStreak,
            int freezesLeft,
            int milestoneReached) {}

    /// Records today as an active day and rolls the streak forward / consumes
    /// a freeze / resets. Idempotent within a calendar day — same-day calls
    /// short-circuit after the activity-day upsert.
    @Transactional
    public StreakUpdateResult recordActiveDay(String userId) {
        LocalDate today = LocalDate.now();
        try {
            dayRepo.recordDay(userId, today);
        } catch (Exception e) {
            log.warn("[Streak] day upsert failed user={}: {}",
                    userId, e.getMessage());
        }

        UserJpaEntity user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return new StreakUpdateResult(0, 0, 0, 0);
        }
        LocalDate last = user.getLastActiveDate();
        if (last != null && last.equals(today)) {
            return new StreakUpdateResult(
                    user.getStreakDays(),
                    user.getLongestStreak(),
                    user.getStreakFreezes(),
                    0);
        }

        int oldStreak = user.getStreakDays();
        int newStreak;
        int freezes = user.getStreakFreezes();
        if (last != null && last.equals(today.minusDays(1))) {
            newStreak = oldStreak + 1;
        } else if (last != null
                && last.equals(today.minusDays(2))
                && freezes > 0
                && oldStreak > 0) {
            // Exactly one missed day and we have a freeze in the bank.
            freezes -= 1;
            newStreak = oldStreak + 1;
            log.info("[Streak] user={} consumed freeze (left={})", userId, freezes);
        } else {
            newStreak = 1;
        }

        int longest = Math.max(user.getLongestStreak(), newStreak);

        int milestoneReached = 0;
        Set<Integer> celebrated = parseMilestones(user.getStreakMilestonesReached());
        for (int m : MILESTONES) {
            if (newStreak >= m && !celebrated.contains(m)) {
                celebrated.add(m);
                milestoneReached = m;
                grantMilestoneBadge(userId, m);
                int starBonus = m / 2;
                user.setStars(user.getStars() + starBonus);
                log.info("[Streak] user={} milestone={} (+{} stars)",
                        userId, m, starBonus);
                break;
            }
        }

        // Earn a freeze on every 7 successful days (only when the streak
        // *crossed* the threshold, not on repeat). Respect L20's higher cap.
        int cap = effectiveFreezeCap(user.getLevel());
        if (newStreak > oldStreak
                && newStreak % 7 == 0
                && freezes < cap) {
            freezes += 1;
            log.info("[Streak] user={} earned freeze (left={}/{} cap)",
                    userId, freezes, cap);
        }

        user.setStreakDays(newStreak);
        user.setLongestStreak(longest);
        user.setLastActiveDate(today);
        user.setStreakFreezes(freezes);
        user.setStreakMilestonesReached(formatMilestones(celebrated));
        userRepo.save(user);

        return new StreakUpdateResult(newStreak, longest, freezes, milestoneReached);
    }

    public int nextMilestone(int currentStreak) {
        for (int m : MILESTONES) {
            if (m > currentStreak) return m;
        }
        return MILESTONES[MILESTONES.length - 1];
    }

    public Set<Integer> celebratedMilestones(String userId) {
        return userRepo.findById(userId)
                .map(u -> parseMilestones(u.getStreakMilestonesReached()))
                .orElseGet(LinkedHashSet::new);
    }

    private void grantMilestoneBadge(String userId, int milestone) {
        try {
            BadgeService.BadgeType type = switch (milestone) {
                case 3 -> BadgeService.BadgeType.STREAK_3;
                case 7 -> BadgeService.BadgeType.STREAK_7;
                case 30 -> BadgeService.BadgeType.STREAK_30;
                default -> null;
            };
            if (type != null) {
                badgeService.grantFirstAction(userId, type);
            }
        } catch (Exception e) {
            log.debug("[Streak] milestone badge skipped: {}", e.getMessage());
        }
    }

    static Set<Integer> parseMilestones(String csv) {
        Set<Integer> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    static String formatMilestones(Set<Integer> milestones) {
        if (milestones.isEmpty()) return null;
        // Sort numerically so the CSV stays stable across reads/writes.
        return milestones.stream()
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /// Test-only helper exposed so other services (e.g. AuthService) can
    /// rebuild a known set during migrations.
    static Set<Integer> milestoneSet() {
        return new HashSet<>(Arrays.stream(MILESTONES).boxed().toList());
    }
}
