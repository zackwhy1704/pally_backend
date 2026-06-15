package com.pally.api.account;

import com.pally.domain.account.AccountService;
import com.pally.domain.account.usecase.DeleteAccountUseCase;
import com.pally.infrastructure.auth.JwtService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Parent ⇄ child account linking.
 *
 * <p>Thin HTTP delegator — all logic lives in {@link AccountService}.
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;
    private final DeleteAccountUseCase deleteAccountUseCase;
    private final JwtService jwtService;

    /**
     * Saves or updates the user's FCM push notification token.
     */
    @PostMapping("/fcm-token")
    public ResponseEntity<ApiResponse<Void>> setFcmToken(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String token = body == null ? null : body.get("token");
        if (token == null || token.isBlank()) {
            throw new BusinessException("token is required", 400);
        }
        accountService.setFcmToken(userId, token);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @PostMapping("/link-code")
    public ResponseEntity<ApiResponse<Map<String, Object>>> issueLinkCode(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(accountService.issueLinkCode(userId)));
    }

    @PostMapping("/claim")
    public ResponseEntity<ApiResponse<Map<String, Object>>> claim(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        return ResponseEntity.ok(ApiResponse.success(accountService.claim(userId, code)));
    }

    @PostMapping("/upgrade-to-parent")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upgradeToParent(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(
                ApiResponse.success(accountService.upgradeToParent(userId)));
    }

    @GetMapping("/family")
    public ResponseEntity<ApiResponse<Map<String, Object>>> family(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(accountService.getFamily(userId)));
    }

    /**
     * Permanently deletes the authenticated user's account and all their data.
     *
     * <p>Required by Apple App Store guideline 5.1.1 and PDPA.
     * Only the authenticated user can delete their own account.
     * Returns 409 Conflict if the user is a PARENT with linked children.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(
            @AuthenticationPrincipal String userId,
            HttpServletRequest request) {

        String jti = null;
        Instant tokenExpiry = null;
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                jti = jwtService.extractJti(token);
                tokenExpiry = jwtService.extractExpiration(token);
            } catch (JwtException e) {
                log.warn("[DeleteAccount] Could not extract jti from token: {}", e.getMessage());
            }
        }

        deleteAccountUseCase.execute(userId, jti, tokenExpiry);
        return ResponseEntity.noContent().build();
    }
}
