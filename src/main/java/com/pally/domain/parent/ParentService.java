package com.pally.domain.parent;

import com.pally.domain.parent.dto.ParentDashboardResponse;
import com.pally.domain.parent.dto.ParentDashboardResponse.SubjectMasteryDto;
import com.pally.domain.parent.dto.ParentDashboardResponse.WeakAreaDto;
import com.pally.domain.parent.dto.WeeklyReportDetail;
import com.pally.domain.parent.dto.WeeklyReportSummary;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.WeeklyReportService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Domain service for parent-self operations: PIN management, personal
 * dashboard aggregation, weekly reports, and screen-time settings.
 *
 * <p>The in-memory PIN-attempt throttle is owned here (not in the
 * controller) so it is shared across all controller instances and is
 * correctly a singleton-scoped bean.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ParentService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 60_000;

    private final UserRepository userRepository;
    private final ActivityLogRepository activityLogRepository;
    private final ActivityLogService activityLogService;
    private final QuizWeakAreaRepository quizWeakAreaRepository;
    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final WeeklyReportService weeklyReportService;

    /** In-memory PIN-attempt throttle keyed by userId. Resets on app restart
     *  and on a successful verify. Persisting these to the DB is overkill for
     *  a 60-second cooldown — a malicious child can't restart the server. */
    private final Map<String, PinAttempts> pinAttempts = new ConcurrentHashMap<>();

    private static final class PinAttempts {
        int fails;
        long lockedUntil;
    }

    // ── PIN management ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public boolean hasPinSet(String userId) {
        return userRepository.getParentPinHash(userId).isPresent();
    }

    @Transactional
    public void setPin(String userId, String pin) {
        userRepository.setParentPinHash(userId, passwordEncoder.encode(pin));
        log.info("[Parent] PIN set for user {}", userId);
    }

    public record VerifyPinResult(boolean verified, boolean firstTimeSetup,
                                  boolean lockedOut, long retryAfterSeconds,
                                  int attemptsRemaining) {}

    @Transactional
    public VerifyPinResult verifyPin(String userId, String pin) {
        long now = System.currentTimeMillis();
        PinAttempts att = pinAttempts.computeIfAbsent(userId, k -> new PinAttempts());
        synchronized (att) {
            if (att.lockedUntil > now) {
                long secs = (att.lockedUntil - now + 999) / 1000;
                return new VerifyPinResult(false, false, true, secs, 0);
            }
        }

        String hash = userRepository.getParentPinHash(userId).orElse(null);
        if (hash == null) {
            // First time — accept and store. The frontend already enforces a
            // confirm-re-entry step before sending, so a typo here would
            // have failed match client-side.
            userRepository.setParentPinHash(userId, passwordEncoder.encode(pin));
            synchronized (att) { att.fails = 0; }
            log.info("[Parent] First-time PIN set for user {}", userId);
            return new VerifyPinResult(true, true, false, 0, MAX_ATTEMPTS);
        }

        boolean ok = passwordEncoder.matches(pin, hash);
        synchronized (att) {
            if (ok) {
                att.fails = 0;
            } else {
                att.fails++;
                if (att.fails >= MAX_ATTEMPTS) {
                    att.lockedUntil = now + LOCKOUT_MS;
                    att.fails = 0;
                    log.warn("[Parent] PIN locked for user {} until {}",
                            userId, att.lockedUntil);
                    return new VerifyPinResult(
                            false, false, true, LOCKOUT_MS / 1000, 0);
                }
            }
        }
        return new VerifyPinResult(
                ok, false, false, 0, Math.max(0, MAX_ATTEMPTS - att.fails));
    }

    @Transactional
    public void resetPin(String userId, String password, String newPin) {
        String passwordHash = userRepository.getPasswordHash(userId)
                .orElseThrow(() -> new BusinessException("Incorrect password", 401));
        if (!passwordEncoder.matches(password, passwordHash)) {
            throw new BusinessException("Incorrect password", 401);
        }
        userRepository.setParentPinHash(userId, passwordEncoder.encode(newPin));
        log.info("[Parent] PIN reset for user {}", userId);
    }

    // ── Dashboard ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ParentDashboardResponse getDashboard(String userId) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        int sessions = activityLogRepository.countSince(userId, weekAgo);
        int xpThisWeek = activityLogRepository.sumXpSince(userId, weekAgo);
        int minutesThisWeek = activityLogRepository.sumMinutesBetween(
                userId, weekAgo, Instant.now());
        List<Integer> weekMinutes = activityLogService.minutesPerDayLast7(userId);

        List<SubjectMasteryDto> subjects =
                weeklyReportService.computeSubjectMastery(userId);

        List<WeakAreaDto> weakAreas = quizWeakAreaRepository.findWeakestTopics(userId, 5)
                .stream()
                .map(r -> new WeakAreaDto((String) r[0],
                        ((Number) r[1]).doubleValue()))
                .toList();

        List<String> reviewTopics = new ArrayList<>();
        for (Avatar a : avatarRepository.findByUserId(userId)) {
            wikiRepository.findReviewRequired(a.getId())
                    .forEach(p -> reviewTopics.add(p.getTitle()));
        }

        return new ParentDashboardResponse(
                sessions, minutesThisWeek, xpThisWeek,
                u.getLevel(), u.getStreakDays(),
                subjects, weekMinutes, weakAreas,
                u.isScreenTimeEnabled(), u.getScreenTimeMinutes(),
                reviewTopics
        );
    }

    // ── Weekly reports ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<WeeklyReportSummary> listReports(String userId) {
        return weeklyReportService.listReports(userId);
    }

    @Transactional(readOnly = true)
    public WeeklyReportDetail getReport(String userId, String weekId) {
        return weeklyReportService.getReport(userId, weekId);
    }

    @Transactional(readOnly = true)
    public Map<String, String> getShareText(String userId, String weekId) {
        LocalDate weekStart;
        try {
            weekStart = weeklyReportService.parseWeekId(weekId);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    "Invalid weekId, expected yyyy-Www (e.g. 2026-W22)", 400);
        }
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant from = weekStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = weekEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        int sessions = activityLogRepository.countBetween(userId, from, to);
        int minutes = activityLogRepository.sumMinutesBetween(userId, from, to);
        int xp = activityLogRepository.sumXpBetween(userId, from, to);
        List<SubjectMasteryDto> subjects = weeklyReportService.computeSubjectMastery(userId);

        String childName = u.getChildName() != null
                ? u.getChildName()
                : (u.getDisplayName() != null ? u.getDisplayName() : "your child");

        StringBuilder sb = new StringBuilder();
        sb.append("📊 Apalchi Weekly Report — ").append(weekId).append('\n').append('\n');
        sb.append("Child: ").append(childName).append('\n');
        sb.append("Sessions: ").append(sessions).append('\n');
        sb.append("Minutes studied: ").append(minutes).append('\n');
        sb.append("XP earned: +").append(xp).append('\n');
        sb.append("Streak: 🔥 ").append(u.getStreakDays()).append(" days\n");
        if (!subjects.isEmpty()) {
            sb.append('\n').append("Subject Mastery:\n");
            for (SubjectMasteryDto s : subjects) {
                sb.append("  • ").append(s.subject()).append(": ")
                        .append(Math.round(s.mastery() * 100))
                        .append("%\n");
            }
        }
        sb.append('\n').append("Keep it up! Open Apalchi to keep learning.");

        return Map.of(
                "text", sb.toString(),
                "subject", "Apalchi Weekly Report — " + childName
        );
    }

    // ── Screen time ────────────────────────────────────────────────────────

    @Transactional
    public void setScreenTime(String userId, Boolean enabled, Number minutes) {
        User u = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        boolean newEnabled = enabled != null ? enabled : u.isScreenTimeEnabled();
        int newMinutes = minutes != null ? minutes.intValue() : u.getScreenTimeMinutes();
        userRepository.setScreenTime(userId, newEnabled, newMinutes);
    }
}
