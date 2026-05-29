package com.pally.api.parent;

import com.pally.api.parent.dto.ParentDashboardResponse;
import com.pally.api.parent.dto.ParentDashboardResponse.SubjectMasteryDto;
import com.pally.api.parent.dto.ParentDashboardResponse.WeakAreaDto;
import com.pally.api.parent.dto.WeeklyReportDetail;
import com.pally.api.parent.dto.WeeklyReportSummary;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.UserStats;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/parent")
@RequiredArgsConstructor
@Slf4j
public class ParentController {

    private final UserJpaRepository userRepo;
    private final ActivityLogJpaRepository activityRepo;
    private final ActivityLogService activityLogService;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final AvatarRepository avatarRepository;
    private final com.pally.domain.knowledge.WikiRepository wikiRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    /// In-memory PIN-attempt throttle keyed by userId. Resets on app restart
    /// and on a successful verify. Persisting these to the DB is overkill for
    /// a 60-second cooldown — a malicious child can't restart the server.
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_MS = 60_000;
    private final java.util.Map<String, PinAttempts> pinAttempts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final class PinAttempts {
        int fails;
        long lockedUntil;
    }

    // ── PIN management ─────────────────────────────────────────────────

    @GetMapping("/pin/status")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> pinStatus(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        boolean hasPin = u.getParentPinHash() != null
                && !u.getParentPinHash().isBlank();
        return ResponseEntity.ok(ApiResponse.success(Map.of("hasPin", hasPin)));
    }

    @PostMapping("/pin/set")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> setPin(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String pin = body.get("pin");
        if (pin == null || !pin.matches("\\d{4,6}")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("PIN must be 4-6 digits", 400));
        }
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        u.setParentPinHash(passwordEncoder.encode(pin));
        userRepo.save(u);
        log.info("[Parent] PIN set for user {}", userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/pin/verify")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyPin(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String pin = body.get("pin");
        if (pin == null || !pin.matches("\\d{4,6}")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("PIN must be 4-6 digits", 400));
        }
        // Lockout check — prevents a child from brute-forcing the 10k-combo
        // space (PINs are 4 digits, fully scriptable).
        long now = System.currentTimeMillis();
        PinAttempts att = pinAttempts.computeIfAbsent(userId, k -> new PinAttempts());
        synchronized (att) {
            if (att.lockedUntil > now) {
                long secs = (att.lockedUntil - now + 999) / 1000;
                return ResponseEntity.ok(ApiResponse.success(Map.of(
                        "verified", false,
                        "firstTimeSetup", false,
                        "lockedOut", true,
                        "retryAfterSeconds", secs)));
            }
        }

        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        String hash = u.getParentPinHash();
        if (hash == null || hash.isBlank()) {
            // First time — accept and store. The frontend already enforces a
            // confirm-re-entry step before sending, so a typo here would
            // have failed match client-side.
            u.setParentPinHash(passwordEncoder.encode(pin));
            userRepo.save(u);
            synchronized (att) { att.fails = 0; }
            log.info("[Parent] First-time PIN set for user {}", userId);
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("verified", true, "firstTimeSetup", true)));
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
                    return ResponseEntity.ok(ApiResponse.success(Map.of(
                            "verified", false,
                            "firstTimeSetup", false,
                            "lockedOut", true,
                            "retryAfterSeconds", LOCKOUT_MS / 1000)));
                }
            }
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "verified", ok,
                "firstTimeSetup", false,
                "attemptsRemaining", Math.max(0, MAX_ATTEMPTS - att.fails))));
    }

    @PostMapping("/pin/reset")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> resetPin(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String password = body.get("password");
        String newPin = body.get("newPin");
        if (newPin == null || !newPin.matches("\\d{4,6}")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("PIN must be 4-6 digits", 400));
        }
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (u.getPasswordHash() == null
                || !passwordEncoder.matches(password, u.getPasswordHash())) {
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("Incorrect password", 401));
        }
        u.setParentPinHash(passwordEncoder.encode(newPin));
        userRepo.save(u);
        log.info("[Parent] PIN reset for user {}", userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Dashboard ───────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<ParentDashboardResponse>> getDashboard(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        int sessions = orZero(activityRepo.countSince(userId, weekAgo));
        int xpThisWeek = orZero(activityRepo.sumXpSince(userId, weekAgo));
        int minutesThisWeek = orZero(activityRepo.sumMinutesBetween(
                userId, weekAgo, Instant.now()));
        List<Integer> weekMinutes = activityLogService.minutesPerDayLast7(userId);

        // Subject mastery — aggregate quiz results by avatar's subject.
        List<SubjectMasteryDto> subjects =
                computeSubjectMastery(userId);

        // Weak areas — top 5 lowest-mastery topics across all avatars.
        List<WeakAreaDto> weakAreas = quizResultRepo.findWeakestTopics(userId, 5)
                .stream()
                .map(r -> new WeakAreaDto((String) r[0],
                        ((Number) r[1]).doubleValue()))
                .toList();

        // R8 — review topics: pages flagged by the quiz feedback loop,
        // aggregated across this user's avatars. Surfaced in the parent
        // dashboard so guardians know what to revisit with their child.
        List<String> reviewTopics = new ArrayList<>();
        for (Avatar a : avatarRepository.findByUserId(userId)) {
            wikiRepository.findReviewRequired(a.getId())
                    .forEach(p -> reviewTopics.add(p.getTitle()));
        }

        return ResponseEntity.ok(ApiResponse.success(new ParentDashboardResponse(
                sessions, minutesThisWeek, xpThisWeek,
                u.getLevel(), u.getStreakDays(),
                subjects, weekMinutes, weakAreas,
                u.isScreenTimeEnabled(), u.getScreenTimeMinutes(),
                reviewTopics
        )));
    }

    // ── Weekly reports ──────────────────────────────────────────────────

    private static final int WEEKS_TO_LIST = 4;

    @GetMapping("/reports")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> listReports(
            @AuthenticationPrincipal String userId) {
        List<WeeklyReportSummary> reports = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < WEEKS_TO_LIST; i++) {
            LocalDate weekStart = isoWeekStart(today.minusWeeks(i));
            LocalDate weekEnd = weekStart.plusDays(6);
            reports.add(buildSummary(userId, weekStart, weekEnd));
        }
        return ResponseEntity.ok(
                ApiResponse.success(Map.of("reports", reports)));
    }

    @GetMapping("/reports/{weekId}/share-text")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, String>>> getShareText(
            @AuthenticationPrincipal String userId,
            @PathVariable String weekId) {
        LocalDate weekStart;
        try {
            weekStart = parseWeekId(weekId);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    "Invalid weekId, expected yyyy-Www (e.g. 2026-W22)", 400);
        }
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant from = weekStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = weekEnd.plusDays(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);

        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        int sessions = orZero(activityRepo.countBetween(userId, from, to));
        int minutes  = orZero(activityRepo.sumMinutesBetween(userId, from, to));
        int xp       = orZero(activityRepo.sumXpBetween(userId, from, to));
        List<SubjectMasteryDto> subjects = computeSubjectMastery(userId);

        String childName = u.getChildName() != null
                ? u.getChildName()
                : (u.getDisplayName() != null ? u.getDisplayName() : "your child");

        StringBuilder sb = new StringBuilder();
        sb.append("📊 Pally Weekly Report — ").append(weekId).append('\n').append('\n');
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
        sb.append('\n').append("Keep it up! Open Pally to keep learning.");

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "text", sb.toString(),
                "subject", "Pally Weekly Report — " + childName
        )));
    }

    @GetMapping("/reports/{weekId}")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<WeeklyReportDetail>> getReport(
            @AuthenticationPrincipal String userId,
            @PathVariable String weekId) {
        LocalDate weekStart;
        try {
            weekStart = parseWeekId(weekId);
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    "Invalid weekId, expected yyyy-Www (e.g. 2026-W22)", 400);
        }
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant from = weekStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = weekEnd.plusDays(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);

        int sessions = orZero(activityRepo.countBetween(userId, from, to));
        int minutes = orZero(activityRepo.sumMinutesBetween(userId, from, to));
        int xp = orZero(activityRepo.sumXpBetween(userId, from, to));
        List<Integer> dailyMinutes = activityLogService
                .minutesPerDayBetween(userId, weekStart, weekEnd);

        List<SubjectMasteryDto> subjects = computeSubjectMastery(userId);
        List<WeakAreaDto> weakAreas = quizResultRepo
                .findWeakestTopics(userId, 5).stream()
                .map(r -> new WeakAreaDto((String) r[0],
                        ((Number) r[1]).doubleValue()))
                .toList();

        String headline = buildHeadline(sessions, minutes, xp);
        String narrative = buildNarrative(sessions, minutes, xp,
                subjects, weakAreas);

        return ResponseEntity.ok(ApiResponse.success(new WeeklyReportDetail(
                weekId, weekStart, weekEnd,
                sessions, minutes, xp,
                dailyMinutes, subjects, weakAreas,
                headline, narrative)));
    }

    private WeeklyReportSummary buildSummary(
            String userId, LocalDate start, LocalDate end) {
        Instant from = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = end.plusDays(1)
                .atStartOfDay().toInstant(ZoneOffset.UTC);
        return new WeeklyReportSummary(
                isoWeekId(start),
                start,
                end,
                orZero(activityRepo.countBetween(userId, from, to)),
                orZero(activityRepo.sumMinutesBetween(userId, from, to)),
                orZero(activityRepo.sumXpBetween(userId, from, to)));
    }

    /// First day (Monday) of the ISO week containing [date].
    private LocalDate isoWeekStart(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        int dow = date.get(wf.dayOfWeek());
        return date.minusDays(dow - 1);
    }

    private String isoWeekId(LocalDate weekStart) {
        WeekFields wf = WeekFields.ISO;
        int year = weekStart.get(wf.weekBasedYear());
        int week = weekStart.get(wf.weekOfWeekBasedYear());
        return String.format("%04d-W%02d", year, week);
    }

    private LocalDate parseWeekId(String weekId) {
        // Expects "yyyy-Www" — split, parse, walk to ISO week Monday.
        int dash = weekId.indexOf('-');
        if (dash < 0 || weekId.length() < 7
                || weekId.charAt(dash + 1) != 'W') {
            throw new DateTimeParseException("bad format", weekId, 0);
        }
        int year = Integer.parseInt(weekId.substring(0, dash));
        int week = Integer.parseInt(weekId.substring(dash + 2));
        // Jan 4 is always in week 1 per ISO 8601.
        LocalDate jan4 = LocalDate.of(year, 1, 4);
        LocalDate jan4Monday = isoWeekStart(jan4);
        return jan4Monday.plusWeeks(week - 1L);
    }

    private String buildHeadline(int sessions, int minutes, int xp) {
        if (sessions == 0) return "No activity this week";
        if (minutes >= 60) {
            return "Solid week — %d min across %d session%s"
                    .formatted(minutes, sessions, sessions == 1 ? "" : "s");
        }
        return "%d session%s, %d min total"
                .formatted(sessions, sessions == 1 ? "" : "s", minutes);
    }

    private String buildNarrative(int sessions, int minutes, int xp,
                                   List<SubjectMasteryDto> subjects,
                                   List<WeakAreaDto> weakAreas) {
        if (sessions == 0) {
            return "Your child didn't open Pally this week. "
                    + "A short daily reminder can help re-engage.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("This week your child earned %d XP across %d minutes of focused practice."
                .formatted(xp, minutes));
        if (!subjects.isEmpty()) {
            var top = subjects.stream()
                    .max((a, b) -> Double.compare(a.mastery(), b.mastery()))
                    .orElse(null);
            if (top != null && top.mastery() > 0) {
                sb.append(" Strongest subject: %s (%.0f%%)."
                        .formatted(top.subject().toLowerCase(Locale.ROOT),
                                top.mastery() * 100));
            }
        }
        if (!weakAreas.isEmpty()) {
            sb.append(" Topic to focus on next week: ")
                    .append(weakAreas.get(0).topic()).append('.');
        }
        return sb.toString();
    }

    // ── Screen time ─────────────────────────────────────────────────────

    @PostMapping("/screen-time")
    @Transactional
    public ResponseEntity<ApiResponse<Void>> setScreenTime(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, Object> body) {
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        Boolean enabled = (Boolean) body.get("enabled");
        Number minutes = (Number) body.get("minutes");
        if (enabled != null) u.setScreenTimeEnabled(enabled);
        if (minutes != null) u.setScreenTimeMinutes(minutes.intValue());
        userRepo.save(u);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /// Compute mastery per subject by joining quiz_question_results with the
    /// user's avatars (since topics live on the avatar). Avatars with no
    /// recorded answers map to 0.0 mastery.
    private List<SubjectMasteryDto> computeSubjectMastery(String userId) {
        List<Avatar> avatars = avatarRepository.findByUserId(userId);
        List<SubjectMasteryDto> result = new ArrayList<>();
        for (Avatar avatar : avatars) {
            List<Object[]> rows = quizResultRepo.findWeakestTopicsByAvatar(
                    userId, avatar.getId(), 50);
            // Average the ratio across topics for a per-avatar mastery score.
            double mastery = rows.stream()
                    .mapToDouble(r -> ((Number) r[1]).doubleValue())
                    .average()
                    .orElse(0.0);
            result.add(new SubjectMasteryDto(
                    avatar.getSubject().name(), mastery));
        }
        return result;
    }

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
