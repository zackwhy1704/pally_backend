package com.pally.api.auth;

import com.pally.infrastructure.persistence.auth.EmailVerificationTokenJpaEntity;
import com.pally.infrastructure.persistence.auth.EmailVerificationTokenJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.PiiUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Item 10.1 — email verification endpoints. Two routes:
 *  - POST /auth/send-verification (authed) issues a token. In dev we
 *    log the masked URL; a real email provider plugs in later behind
 *    the same interface.
 *  - POST /auth/verify-email {token} (public) flips the user's
 *    {@code email_verified} flag.
 *
 * <p>Basic app usage is intentionally NOT blocked on verification —
 * kids need a fast start — but ReferralService's activation gate
 * checks this flag before minting rewards (anti-fraud).
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationController {

    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EmailVerificationTokenJpaRepository tokenRepo;
    private final UserJpaRepository userRepo;
    private final com.pally.domain.referral.ReferralService referralService;

    @PostMapping("/send-verification")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> sendVerification(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (user.isEmailVerified()) {
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "alreadyVerified", true)));
        }
        String token = newToken();
        EmailVerificationTokenJpaEntity row = new EmailVerificationTokenJpaEntity();
        row.setToken(token);
        row.setUserId(userId);
        row.setExpiresAt(Instant.now().plus(TOKEN_TTL));
        row.setCreatedAt(Instant.now());
        tokenRepo.save(row);
        // Dev: log a masked email + the token. Wire a real provider
        // (Resend, Postmark, SES) here before prod traffic ramps.
        log.info("[Verify] dispatch user={} email={} (TTL {}h)",
                userId, PiiUtil.maskEmail(user.getEmail()), TOKEN_TTL.toHours());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "sent", true,
                "expiresAt", row.getExpiresAt().toString())));
    }

    @PostMapping("/verify-email")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> verifyEmail(
            @RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        if (token == null || token.isBlank()) {
            throw new BusinessException("token is required", 400);
        }
        EmailVerificationTokenJpaEntity row = tokenRepo.findById(token)
                .orElseThrow(() -> new BusinessException(
                        "That link is invalid or already used.", 404));
        if (row.getUsedAt() != null) {
            throw new BusinessException(
                    "That link has already been used.", 410);
        }
        if (Instant.now().isAfter(row.getExpiresAt())) {
            throw new BusinessException(
                    "That link has expired — request a new one.", 410);
        }
        UserJpaEntity user = userRepo.findById(row.getUserId()).orElseThrow(
                () -> new BusinessException("User not found", 404));
        user.setEmailVerified(true);
        userRepo.save(user);
        row.setUsedAt(Instant.now());
        tokenRepo.save(row);
        // If a referral was held back pending verification, this is its
        // moment — onFirstQuizAnswer is no-op when there's no pending
        // referral or the user hasn't taken a quiz yet.
        try {
            referralService.onFirstQuizAnswer(user.getId());
        } catch (Exception e) {
            log.warn("[Verify] referral-retry skipped: {}", e.getMessage());
        }
        log.info("[Verify] user={} verified", user.getId());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "verified", true)));
    }

    /// 32 bytes ≈ 64-char hex. Big enough to be unguessable.
    private String newToken() {
        byte[] buf = new byte[32];
        RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
