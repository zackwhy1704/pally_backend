package com.pally.api.parent;

import com.pally.api.parent.dto.ParentDashboardResponse;
import com.pally.api.parent.dto.ParentDashboardResponse.SubjectMasteryDto;
import com.pally.api.parent.dto.ParentDashboardResponse.WeakAreaDto;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    private final BCryptPasswordEncoder passwordEncoder;

    // ── PIN management ─────────────────────────────────────────────────

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
        UserJpaEntity u = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        String hash = u.getParentPinHash();
        if (hash == null || hash.isBlank()) {
            // First time — accept and store
            u.setParentPinHash(passwordEncoder.encode(pin));
            userRepo.save(u);
            log.info("[Parent] First-time PIN set for user {}", userId);
            return ResponseEntity.ok(ApiResponse.success(
                    Map.of("verified", true, "firstTimeSetup", true)));
        }

        boolean ok = passwordEncoder.matches(pin, hash);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("verified", ok, "firstTimeSetup", false)));
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

        return ResponseEntity.ok(ApiResponse.success(new ParentDashboardResponse(
                sessions, minutesThisWeek, xpThisWeek,
                u.getLevel(), u.getStreakDays(),
                subjects, weekMinutes, weakAreas,
                u.isScreenTimeEnabled(), u.getScreenTimeMinutes()
        )));
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
