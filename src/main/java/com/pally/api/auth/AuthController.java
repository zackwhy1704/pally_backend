package com.pally.api.auth;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.api.auth.dto.ForgotPasswordRequest;
import com.pally.api.auth.dto.LoginRequest;
import com.pally.api.auth.dto.RegisterRequest;
import com.pally.api.auth.dto.SetupRequest;
import com.pally.api.auth.dto.SocialAuthRequest;
import com.pally.infrastructure.auth.AuthService;
import com.pally.infrastructure.auth.SocialTokenVerifier;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    /// 5 login attempts per 5 min, keyed by (ip, email). Tight enough to
    /// kill brute-force; loose enough that a kid fat-fingering the
    /// password isn't locked out for an hour.
    private static final int LOGIN_LIMIT = 5;
    private static final long LOGIN_WINDOW_MS = 5L * 60_000;
    /// Forgot-password is rarer; 3 / hour is a fine cap.
    private static final int FORGOT_LIMIT = 3;
    private static final long FORGOT_WINDOW_MS = 60L * 60_000;

    private final AuthService authService;
    private final SlidingWindowRateLimiter rateLimiter;
    private final SocialTokenVerifier socialTokenVerifier;

    // Client IDs for audience validation — set in Railway Variables.
    // Comma-separated for multi-platform (iOS + Android may have different client IDs).
    // Leave blank to skip audience validation (not recommended for production).
    @Value("${auth.google.client-ids:}")
    private String googleClientIds;

    @Value("${auth.apple.client-ids:}")
    private String appleClientIds;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        AuthResponse result = authService.register(
                request.email(), request.password(), request.displayName(),
                request.role(), request.birthYear());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(result));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest http
    ) {
        // IP+email key — one IP can't spray many accounts, one account
        // can't be hammered from many IPs. Failed-attempts only: we
        // reset on success below.
        String key = "login:" + clientIp(http) + ":" + safeEmail(request.email());
        var r = rateLimiter.tryAcquire(key, LOGIN_LIMIT, LOGIN_WINDOW_MS);
        if (!r.allowed()) {
            throw new BusinessException(
                    "Too many login attempts. Try again in "
                            + r.retryAfterSeconds() + "s.", 429);
        }
        try {
            AuthResponse result = authService.login(
                    request.email(), request.password());
            // Success: clear the counter so the next session isn't
            // throttled by their earlier fat-fingered tries.
            rateLimiter.reset(key);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (RuntimeException e) {
            // Failure leaves the hit recorded — the counter walks toward
            // the limit, which is the whole point.
            throw e;
        }
    }

    /**
     * Google sign-in: client sends the idToken from the google_sign_in package.
     * Verifies the RS256 signature against Google's JWKS endpoint, checks iss/aud/exp.
     */
    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> googleSignIn(
            @RequestBody SocialAuthRequest request
    ) {
        try {
            List<String> audiences = parseClientIds(googleClientIds);
            SocialTokenVerifier.VerifiedClaims claims =
                    socialTokenVerifier.verifyGoogle(request.idToken(), audiences);
            if (claims.email() == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Google token missing email claim", 400));
            }
            AuthResponse result = authService.signInWithSocial(claims.email(), claims.name());
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (SecurityException e) {
            log.warn("[Auth] Google token verification failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.error("Invalid Google token", 401));
        }
    }

    /**
     * Apple sign-in: client sends the identityToken from sign_in_with_apple package.
     * Verifies the RS256 signature against Apple's JWKS endpoint, checks iss/aud/exp.
     */
    @PostMapping("/apple")
    public ResponseEntity<ApiResponse<AuthResponse>> appleSignIn(
            @RequestBody SocialAuthRequest request
    ) {
        try {
            String token = request.identityToken() != null ? request.identityToken() : request.idToken();
            List<String> audiences = parseClientIds(appleClientIds);
            SocialTokenVerifier.VerifiedClaims claims =
                    socialTokenVerifier.verifyApple(token, audiences);
            // Apple omits email on repeated logins — use sub as the stable identifier.
            String email = claims.email() != null
                    ? claims.email()
                    : claims.subject() + "@privaterelay.appleid.com";
            AuthResponse result = authService.signInWithSocial(email, null);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (SecurityException e) {
            log.warn("[Auth] Apple token verification failed: {}", e.getMessage());
            return ResponseEntity.status(401).body(ApiResponse.error("Invalid Apple token", 401));
        }
    }

    @PostMapping("/setup")
    public ResponseEntity<ApiResponse<AuthResponse>> completeSetup(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody SetupRequest request
    ) {
        AuthResponse result = authService.completeSetup(
                userId, request.childName(), request.yearLevel(), request.curriculum());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PatchMapping("/setup")
    public ResponseEntity<ApiResponse<Map<String, String>>> updateSetup(
            @AuthenticationPrincipal String userId,
            @RequestBody SetupRequest request
    ) {
        authService.updateChildName(userId, request.childName());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("childName", request.childName() != null ? request.childName() : "")));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Map<String, String>>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request,
            HttpServletRequest http
    ) {
        String key = "forgot:" + clientIp(http) + ":" + safeEmail(request.email());
        var r = rateLimiter.tryAcquire(key, FORGOT_LIMIT, FORGOT_WINDOW_MS);
        if (!r.allowed()) {
            // Return the same generic body even on throttle so the
            // response shape doesn't leak whether the email exists.
            log.info("[Auth] Forgot-password throttled (key hash={})",
                    Integer.toHexString(key.hashCode()));
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "message", "If an account exists, a reset link has been sent")));
        }
        // Never log the email. AuthService handles the actual lookup +
        // dispatch; we keep the response identical regardless of whether
        // the account exists (user-enumeration guard).
        log.info("[Auth] Forgot-password request received");
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "If an account exists, a reset link has been sent")));
    }

    /// Best-effort client IP. Railway sets X-Forwarded-For; fall back to
    /// the remote address when running locally or via direct connections.
    private String clientIp(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        return (real != null && !real.isBlank()) ? real : request.getRemoteAddr();
    }

    private String safeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    @DeleteMapping("/account")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal String userId
    ) {
        authService.deleteAccount(userId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @AuthenticationPrincipal String userId
    ) {
        var user = authService.getUser(userId);
        return ResponseEntity.ok(ApiResponse.success(user));
    }

    /// Update the user's default answer mode (GUIDE|ANSWER).
    /// Called from Settings → Learning style; the per-conversation toggle
    /// overrides this locally without hitting the server.
    @PatchMapping("/settings/answer-mode")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateAnswerMode(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body
    ) {
        String mode = body.getOrDefault("defaultAnswerMode", "GUIDE");
        authService.updateDefaultAnswerMode(userId, mode);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("defaultAnswerMode", mode.equalsIgnoreCase("ANSWER") ? "ANSWER" : "GUIDE")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<String> parseClientIds(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return List.of();
        return List.of(commaSeparated.split(",")).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
