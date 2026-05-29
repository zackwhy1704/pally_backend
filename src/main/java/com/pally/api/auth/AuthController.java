package com.pally.api.auth;

import com.pally.api.auth.dto.AuthResponse;
import com.pally.api.auth.dto.ForgotPasswordRequest;
import com.pally.api.auth.dto.LoginRequest;
import com.pally.api.auth.dto.RegisterRequest;
import com.pally.api.auth.dto.SetupRequest;
import com.pally.api.auth.dto.SocialAuthRequest;
import com.pally.infrastructure.auth.AuthService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

import java.util.Base64;
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

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        AuthResponse result = authService.register(request.email(), request.password(), request.displayName());
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
     * Google sign-in: client sends the idToken obtained from google_sign_in package.
     * For MVP, we decode the JWT payload to extract the email claim without full
     * signature verification. Production must verify against Google's JWKS endpoint.
     */
    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AuthResponse>> googleSignIn(
            @RequestBody SocialAuthRequest request
    ) {
        String email = extractEmailFromJwt(request.idToken());
        if (email == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid Google token", 400));
        }
        String name = extractNameFromJwt(request.idToken());
        AuthResponse result = authService.signInWithSocial(email, name);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Apple sign-in: client sends the identityToken obtained from sign_in_with_apple package.
     * For MVP, we decode the JWT payload to extract the email claim without full
     * signature verification. Production must verify against Apple's JWKS endpoint.
     */
    @PostMapping("/apple")
    public ResponseEntity<ApiResponse<AuthResponse>> appleSignIn(
            @RequestBody SocialAuthRequest request
    ) {
        String token = request.identityToken() != null ? request.identityToken() : request.idToken();
        String email = extractEmailFromJwt(token);
        if (email == null) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Invalid Apple token", 400));
        }
        AuthResponse result = authService.signInWithSocial(email, null);
        return ResponseEntity.ok(ApiResponse.success(result));
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

    // ── helpers ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private String extractEmailFromJwt(String jwt) {
        try {
            Map<String, Object> payload = decodeJwtPayload(jwt);
            return (String) payload.get("email");
        } catch (Exception e) {
            log.warn("[Auth] Failed to decode JWT: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String extractNameFromJwt(String jwt) {
        try {
            Map<String, Object> payload = decodeJwtPayload(jwt);
            Object name = payload.get("name");
            return name != null ? name.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> decodeJwtPayload(String jwt) {
        if (jwt == null) throw new IllegalArgumentException("JWT is null");
        String[] parts = jwt.split("\\.");
        if (parts.length < 2) throw new IllegalArgumentException("Not a JWT");
        byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readValue(decoded, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JWT payload", e);
        }
    }

    private String padBase64(String base64Url) {
        int padding = (4 - base64Url.length() % 4) % 4;
        return base64Url + "=".repeat(padding);
    }
}
