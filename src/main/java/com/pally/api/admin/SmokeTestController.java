package com.pally.api.admin;

import com.pally.domain.notification.WeeklyEmailScheduler;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.ocr.GeminiVisionOcrService;
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
import java.util.Base64;
import java.util.LinkedHashMap;
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
    private final GeminiVisionOcrService geminiOcr;

    /** A minimal but valid 1×1 PNG — enough to exercise the multimodal OCR path. */
    private static final String TEST_PNG_B64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYPhfDwAChwGA"
            + "60e6kgAAAABJRU5ErkJggg==";

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

    /**
     * Live Gemini probe against the production API key. Proves, on demand,
     * whether the configured models resolve (HTTP 200) or 404 for live traffic —
     * for BOTH the text path (wiki compilation) and the vision path (image OCR).
     *
     * <p>A 404 means the model is retired/unavailable for this key's project; any
     * non-404 status proves the model resolves (the model name is validated before
     * request-body validation, so even a 400 rules out the "retired model" 404).
     */
    @PostMapping("/gemini")
    public ResponseEntity<ApiResponse<Map<String, Object>>> smokeGemini(
            @AuthenticationPrincipal String userId) {
        byte[] testPng = Base64.getDecoder().decode(TEST_PNG_B64);

        // The exact OCR path live users' images fall back to (Gemini vision).
        var visionOcr = geminiOcr.probe(geminiOcr.visionModel(), testPng);
        // The text path used by wiki compilation (primary tier).
        var text25 = geminiOcr.probe("gemini-2.5-flash", null);
        // The configured secondary tier — expected to 404 for this key.
        var text20 = geminiOcr.probe("gemini-2.0-flash", null);

        log.info("[Smoke] Gemini probe by admin={}: visionOcr={} text2.5={} text2.0={}",
                userId, visionOcr.statusCode(), text25.statusCode(), text20.statusCode());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apiKeyConfigured", geminiOcr.isAvailable());
        out.put("ocrPrimaryEngine", "claude-vision");
        out.put("ocrFallbackEngine", "gemini-vision (" + geminiOcr.visionModel() + ")");
        out.put("visionOcr", toMap(visionOcr));
        out.put("text_gemini_2_5_flash", toMap(text25));
        out.put("text_gemini_2_0_flash", toMap(text20));
        out.put("verdict", visionOcr.ok()
                ? "OCR vision model resolves in prod — no 404 for live users."
                : "OCR vision model did NOT return 200 — investigate (status="
                  + visionOcr.statusCode() + ").");
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    private static Map<String, Object> toMap(GeminiVisionOcrService.ProbeResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("model", r.model());
        m.put("kind", r.kind());
        m.put("status", r.statusCode());
        m.put("ok", r.ok());
        m.put("elapsedMs", r.elapsedMs());
        m.put("bodySnippet", r.bodySnippet());
        return m;
    }
}
