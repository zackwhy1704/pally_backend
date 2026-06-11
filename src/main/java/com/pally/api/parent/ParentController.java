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
import com.pally.domain.progress.WeeklyReportService;
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
    private final WeeklyReportService weeklyReportService;

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
                weeklyReportService.computeSubjectMastery(userId);

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

    @GetMapping("/reports")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> listReports(
            @AuthenticationPrincipal String userId) {
        List<WeeklyReportSummary> reports = weeklyReportService.listReports(userId);
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
            weekStart = weeklyReportService.parseWeekId(weekId);
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
        List<SubjectMasteryDto> subjects = weeklyReportService.computeSubjectMastery(userId);

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
        try {
            WeeklyReportDetail detail = weeklyReportService.getReport(userId, weekId);
            return ResponseEntity.ok(ApiResponse.success(detail));
        } catch (DateTimeParseException e) {
            throw new BusinessException(
                    "Invalid weekId, expected yyyy-Www (e.g. 2026-W22)", 400);
        }
    }

    // Report helper methods now live in WeeklyReportService.

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

    // computeSubjectMastery now lives in WeeklyReportService.

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
