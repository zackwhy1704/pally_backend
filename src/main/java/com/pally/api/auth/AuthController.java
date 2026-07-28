package com.pally.api.auth;

import com.pally.domain.auth.dto.AuthResponse;
import com.pally.api.auth.dto.ForgotPasswordRequest;
import com.pally.api.auth.dto.LoginRequest;
import com.pally.api.auth.dto.RegisterRequest;
import com.pally.api.auth.dto.ResetPasswordRequest;
import com.pally.api.auth.dto.LinkSocialRequest;
import com.pally.api.auth.dto.CompleteProfileRequest;
import com.pally.api.auth.dto.SetupRequest;
import com.pally.api.auth.dto.SocialAuthRequest;
import com.pally.domain.centre.CentreInviteService;
import com.pally.infrastructure.auth.AuthCookieService;
import com.pally.infrastructure.auth.AuthService;
import com.pally.infrastructure.auth.SocialTokenVerifier;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
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
    private final CentreInviteService inviteService;
    private final SlidingWindowRateLimiter rateLimiter;
    private final SocialTokenVerifier socialTokenVerifier;
    private final AuthCookieService authCookieService;

    // Client IDs for audience validation — set in Railway Variables.
    // Comma-separated for multi-platform (iOS + Android may have different client IDs).
    // Leave blank to skip audience validation (not recommended for production).
    @Value("${auth.google.client-ids:}")
    private String googleClientIds;

    @Value("${auth.apple.client-ids:}")
    private String appleClientIds;

    /**
     * Bearer→cookie bridge. For a request authenticated by a valid Bearer JWT, re-issue
     * the SAME httpOnly auth cookie the login path sets — so a legacy web session (bearer
     * in localStorage, no cookie) establishes the cookie on boot, and the future edge
     * middleware never locks out a mid-migration session (no forced re-login).
     *
     * <p>This route is NOT in the permitAll list, so the security filter rejects anything
     * without a valid bearer/cookie (401) before we get here. We set the cookie from the
     * SAME token that authenticated this request — never a body/query token, never a fresh
     * token (no lifetime extension). No-op unless AUTH_COOKIE_DOMAIN is set.
     */
    @PostMapping("/cookie-bridge")
    public ResponseEntity<Void> cookieBridge(HttpServletRequest request, HttpServletResponse response) {
        String token = bearerToken(request);
        if (token == null) {
            token = AuthCookieService.readAuthCookie(request); // authenticated via an existing cookie
        }
        if (token != null) {
            authCookieService.setAuthCookie(response, token);
        }
        return ResponseEntity.noContent().build();
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        return (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request,
            HttpServletResponse response
    ) {
        // FAIL-CLOSED (tolerance window closed 2026-07-09). This used to default a blank
        // role + null birth year to role="adult" (age-exempt) — a deploy-order tolerance
        // from when the web client relied on it. But "unknown shape -> age-exempt ADULT"
        // is exactly the unknown->adult pattern this codebase spent a branch killing: if
        // any client ever sent this shape it would silently mint age-exempt accounts
        // (age-exempt minors) behind a reassuring default. The real web client (memoly)
        // sends an explicit role:"adult" — VERIFIED live on prod (deployment ed6d5e0);
        // mobile students use /onboard/quick and always carry a birth year. So a blank
        // role + no birth year is no longer a valid shape — REJECTED, not defaulted.
        String role = request.role();
        if ((role == null || role.isBlank()) && request.birthYear() == null) {
            throw new BusinessException(
                    "A birth year or an account role is required to register.", 400);
        }
        AuthResponse result = authService.register(
                request.email(), request.password(), request.displayName(),
                role, request.birthYear(), request.parentEmail());
        // Web cookie-auth (no-op unless AUTH_COOKIE_DOMAIN is set); body token unchanged for mobile.
        authCookieService.setAuthCookie(response, result.token());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(result));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest http,
            HttpServletResponse response
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
            authCookieService.setAuthCookie(response, result.token());
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
            @RequestBody SocialAuthRequest request,
            HttpServletResponse response
    ) {
        try {
            List<String> audiences = parseClientIds(googleClientIds);
            SocialTokenVerifier.VerifiedClaims claims =
                    socialTokenVerifier.verifyGoogle(request.idToken(), audiences);
            if (claims.email() == null) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("Google token missing email claim", 400));
            }
            AuthResponse result = authService.signInWithSocial(
                    claims.email(), claims.emailVerified(), claims.name(), claims.provider(), claims.subject());
            authCookieService.setAuthCookie(response, result.token());
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
            // Apple omits email on repeated logins — synthesize a stable per-sub relay
            // address so the same Apple user keys to the same account. That synthetic
            // address is Apple-controlled and stable, so it counts as verified.
            boolean hasRealEmail = claims.email() != null;
            String email = hasRealEmail
                    ? claims.email()
                    : claims.subject() + "@privaterelay.appleid.com";
            AuthResponse result = authService.signInWithSocial(
                    email, claims.emailVerified() || !hasRealEmail, null, claims.provider(), claims.subject());
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

    /// Complete a PENDING_PROFILE (social) or legacy null-birthYear account by recording
    /// the birth year. Authenticated (the user has a session); NOT consent-gated (this IS
    /// the step that clears the gate). Under-13 → routes into the parental-consent flow.
    @PostMapping("/complete-profile")
    public ResponseEntity<ApiResponse<AuthResponse>> completeProfile(
            @AuthenticationPrincipal String userId,
            @Valid @RequestBody CompleteProfileRequest request,
            HttpServletResponse response
    ) {
        AuthResponse result = authService.completeProfile(
                userId, request.birthYear(), request.parentEmail());
        authCookieService.setAuthCookie(response, result.token());
        return ResponseEntity.ok(ApiResponse.success(result));
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
        // Never log the email. requestPasswordReset does the lookup + dispatch and is a
        // no-op for a non-existent account; the response is identical either way
        // (user-enumeration guard).
        log.info("[Auth] Forgot-password request received");
        authService.requestPasswordReset(request.email());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "If an account exists, a reset link has been sent")));
    }

    /// Complete a password reset from the emailed single-use token. Invalidates all
    /// existing sessions and does NOT auto-login (the user signs in fresh).
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Map<String, String>>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        authService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "Your password has been reset. Please sign in.")));
    }

    /// Link a social provider to an existing PASSWORD account (challenge A): verify the
    /// password, then attach the provider sub + issue a fresh session.
    @PostMapping("/link/password")
    public ResponseEntity<ApiResponse<AuthResponse>> linkByPassword(
            @Valid @RequestBody LinkSocialRequest request,
            HttpServletResponse response
    ) {
        SocialTokenVerifier.VerifiedClaims claims = verifySocial(request);
        AuthResponse result = authService.linkSocialByPassword(
                claims.email(), claims.emailVerified(), claims.provider(),
                claims.subject(), request.password());
        authCookieService.setAuthCookie(response, result.token());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /// Challenge B step 1: email a 6-digit link code to a passwordless account's owner.
    @PostMapping("/link/request-code")
    public ResponseEntity<ApiResponse<Map<String, String>>> requestLinkCode(
            @Valid @RequestBody LinkSocialRequest request
    ) {
        SocialTokenVerifier.VerifiedClaims claims = verifySocial(request);
        authService.requestSocialLinkCode(
                claims.email(), claims.emailVerified(), claims.provider(), claims.subject());
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("message", "If that account exists, a code has been sent")));
    }

    /// Challenge B step 2: verify the emailed code, attach the provider sub, fresh session.
    @PostMapping("/link/verify-code")
    public ResponseEntity<ApiResponse<AuthResponse>> verifyLinkCode(
            @Valid @RequestBody LinkSocialRequest request,
            HttpServletResponse response
    ) {
        SocialTokenVerifier.VerifiedClaims claims = verifySocial(request);
        AuthResponse result = authService.linkSocialByCode(
                claims.email(), claims.emailVerified(), claims.provider(), request.code());
        authCookieService.setAuthCookie(response, result.token());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /// Verify the social token on a link request — same JWKS verification as sign-in.
    private SocialTokenVerifier.VerifiedClaims verifySocial(LinkSocialRequest request) {
        if ("apple".equalsIgnoreCase(request.provider())) {
            String token = request.identityToken() != null ? request.identityToken() : request.idToken();
            return socialTokenVerifier.verifyApple(token, parseClientIds(appleClientIds));
        }
        return socialTokenVerifier.verifyGoogle(request.idToken(), parseClientIds(googleClientIds));
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

    /**
     * @deprecated REMOVED (410 GONE). This was a bearer-only IMMEDIATE hard-delete: a
     * valid session alone triggered a full, graceless, irreversible purge — it violated
     * the locked "a bearer token alone can never initiate deletion" rule and bypassed the
     * entire grace / re-auth / restore flow. It is retired rather than repurposed so a
     * second live deletion surface can't drift from the canonical one. Clients must use
     * {@code POST /api/v1/account/delete} (re-auth + 14-day grace) and
     * {@code POST /api/v1/account/restore}.
     */
    @Deprecated
    @DeleteMapping("/account")
    public ResponseEntity<ApiResponse<Void>> deleteAccount() {
        return ResponseEntity.status(HttpStatus.GONE).body(ApiResponse.error(
                "This way of deleting your account has been removed. Please update the app "
                + "to delete your account.", 410));
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

    /// Update the user's preferred UI locale ('en' | 'zh'). Called from Settings → Language and,
    /// at account creation, mirrored from the onboarding language picker. UI chrome only — this does
    /// NOT change what language any avatar generates in (that's the avatar's content_language) and it
    /// never retags existing artifacts. An unsupported value returns 400, not a silent default.
    @PatchMapping("/settings/locale")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updatePreferredLocale(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body
    ) {
        String locale = com.pally.domain.i18n.SupportedLanguage.validate(body.get("preferredLocale"));
        authService.updatePreferredLocale(userId, locale);
        return ResponseEntity.ok(ApiResponse.success(Map.of("preferredLocale", locale)));
    }

    // ── Centre invite (public — no auth required) ─────────────────────────────

    @GetMapping("/invite/{token}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInvite(
            @PathVariable String token
    ) {
        return ResponseEntity.ok(ApiResponse.success(inviteService.getInvite(token)));
    }

    @PostMapping("/accept-invite")
    public ResponseEntity<ApiResponse<Map<String, Object>>> acceptInvite(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, Object> body
    ) {
        return ResponseEntity.ok(ApiResponse.success(inviteService.acceptInvite(userId, body)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<String> parseClientIds(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) return List.of();
        return List.of(commaSeparated.split(",")).stream()
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
