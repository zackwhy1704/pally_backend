package com.pally.api.auth;

import com.pally.api.auth.dto.AuthResponse;
import com.pally.api.auth.dto.LoginRequest;
import com.pally.api.auth.dto.RegisterRequest;
import com.pally.api.auth.dto.SetupRequest;
import com.pally.api.auth.dto.SocialAuthRequest;
import com.pally.infrastructure.auth.AuthService;
import com.pally.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Base64;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        AuthResponse result = authService.register(request.email(), request.password(), request.displayName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(result));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        AuthResponse result = authService.login(request.email(), request.password());
        return ResponseEntity.ok(ApiResponse.success(result));
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
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody SetupRequest request
    ) {
        AuthResponse result = authService.completeSetup(
                userId, request.childName(), request.yearLevel(), request.curriculum());
        return ResponseEntity.ok(ApiResponse.success(result));
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
