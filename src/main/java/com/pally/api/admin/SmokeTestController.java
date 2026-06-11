package com.pally.api.admin;

import com.pally.domain.notification.WeeklyEmailScheduler;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.push.FcmService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Admin-only smoke-test endpoints for verifying email, push, and
 * scheduled-job pipelines. Gated by {@code hasRole('ADMIN')} via
 * SecurityConfig's {@code /api/v1/admin/**} rule.
 */
@RestController
@RequestMapping("/api/v1/admin/smoke")
@RequiredArgsConstructor
@Slf4j
public class SmokeTestController {

    private final EmailService emailService;
    private final FcmService fcmService;
    private final UserJpaRepository userRepo;
    private final WeeklyEmailScheduler weeklyEmailScheduler;

    @PostMapping("/email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> smokeEmail(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "") String to) {
        String recipient = to;
        if (recipient.isBlank()) {
            recipient = userRepo.findById(userId)
                    .map(UserJpaEntity::getEmail)
                    .orElse("");
        }
        if (recipient.isBlank()) {
            throw new BusinessException("No email address available", 400);
        }
        emailService.sendHtml(recipient, "Apalchi smoke test ✅",
                "<h2 style='color:#7042ED'>Apalchi Email Smoke Test</h2>"
                + "<p>If you can read this, Resend SMTP is live.</p>"
                + "<p>Check headers for spf=pass, dkim=pass.</p>"
                + "<p style='color:#999'>Sent at: " + Instant.now() + "</p>");
        log.info("[Smoke] Email test sent to {} by admin={}", recipient, userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "configured", emailService.isConfigured(),
                "sentTo", recipient,
                "from", emailService.getFrom())));
    }

    @PostMapping("/push")
    public ResponseEntity<ApiResponse<Map<String, Object>>> smokePush(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue = "") String targetUserId) {
        String target = targetUserId.isBlank() ? userId : targetUserId;
        UserJpaEntity user = userRepo.findById(target)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        boolean hasToken = user.getFcmToken() != null && !user.getFcmToken().isBlank();
        fcmService.sendToUser(target, "Apalchi smoke test ✅",
                "Push pipeline is live.");
        log.info("[Smoke] Push test to user={} by admin={}", target, userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "fcmConfigured", fcmService.isConfigured(),
                "userHasToken", hasToken,
                "userId", target)));
    }

    @PostMapping("/weekly-email")
    public ResponseEntity<ApiResponse<String>> triggerWeeklyEmail(
            @AuthenticationPrincipal String userId) {
        log.info("[Smoke] Weekly email triggered by admin={}", userId);
        weeklyEmailScheduler.sendWeeklyReports();
        return ResponseEntity.ok(ApiResponse.success("Weekly email triggered"));
    }
}
