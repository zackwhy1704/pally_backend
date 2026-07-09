package com.pally.api.account;

import com.pally.domain.account.AccountDeletionService;
import com.pally.domain.account.AccountService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
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

import java.util.Map;

/**
 * Parent ⇄ child account linking + account deletion (request/grace flow).
 *
 * <p>Thin HTTP delegator — all logic lives in {@link AccountService} /
 * {@link AccountDeletionService}.
 */
@RestController
@RequestMapping("/api/v1/account")
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountService accountService;
    private final AccountDeletionService accountDeletionService;

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
     * Requests deletion of the authenticated account (Apple 5.1.1 / PDPA erasure).
     * Re-auth is MANDATORY — a bearer token alone can never initiate deletion:
     * <ul>
     *   <li>password accounts send {@code {"password": "..."}};</li>
     *   <li>passwordless (social) accounts first call {@code POST /delete/send-code}
     *       to receive an emailed code, then send {@code {"code": "123456"}}.</li>
     * </ul>
     * On success the account enters a grace window (logged out everywhere via the
     * session-epoch bump); the response carries the grace end date and whether the
     * user must cancel a store IAP manually. 409 CENTRE_NOT_EMPTY if the caller owns a
     * non-empty centre; 409 if a parent still has linked children; 401 on bad re-auth;
     * 429 when rate-limited.
     */
    @PostMapping("/delete")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestDeletion(
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) Map<String, String> body) {
        AccountDeletionService.DeletionRequestResult res = accountDeletionService.requestDeletion(
                userId,
                body == null ? null : body.get("password"),
                body == null ? null : body.get("code"));
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "graceEndsAt", res.graceEndsAt().toString(),
                "needsManualCancellation", res.needsManualCancellation())));
    }

    /**
     * Emails a passwordless (social) account a 6-digit deletion confirmation code.
     * A no-op (still 200) for password accounts or accounts with no email on file, so
     * the endpoint never reveals which kind an account is.
     */
    @PostMapping("/delete/send-code")
    public ResponseEntity<ApiResponse<Void>> sendDeleteCode(
            @AuthenticationPrincipal String userId) {
        accountDeletionService.sendDeleteCodeIfPasswordless(userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    /**
     * PUBLIC (unauthenticated) delete-by-email request (Phase 2 / memoly public page).
     * Always returns the SAME non-enumerating message so it can't reveal whether an
     * account exists; if it does, a single-use confirm link is emailed. Rate-limited per
     * address (429).
     */
    @PostMapping("/delete/request-by-email")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestDeletionByEmail(
            @RequestBody(required = false) Map<String, String> body) {
        accountDeletionService.requestDeletionByEmail(body == null ? null : body.get("email"));
        return ResponseEntity.ok(ApiResponse.success(Map.of("message",
                "If an account exists for that email, we've emailed instructions to delete it.")));
    }

    /**
     * PUBLIC confirm of a delete-by-email request — the emailed single-use token is the
     * authorization. Runs the same grace pipeline as the authenticated request; 409
     * CENTRE_NOT_EMPTY if the account owns a non-empty centre; 400 on a bad/expired token.
     */
    @PostMapping("/delete/confirm")
    public ResponseEntity<ApiResponse<Map<String, Object>>> confirmDeletionByToken(
            @RequestBody(required = false) Map<String, String> body) {
        AccountDeletionService.DeletionRequestResult res =
                accountDeletionService.confirmDeletionByToken(body == null ? null : body.get("token"));
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "graceEndsAt", res.graceEndsAt().toString(),
                "needsManualCancellation", res.needsManualCancellation())));
    }

    /**
     * Cancels a pending deletion during the grace window. UNAUTHENTICATED (the account
     * has no valid session during grace — the epoch bump killed it): authorised by the
     * emailed restore {@code token}, or by {@code email}+{@code password} re-auth (the
     * login-during-grace path). Returns {@code {restored: true|false}}.
     */
    @PostMapping("/restore")
    public ResponseEntity<ApiResponse<Map<String, Object>>> restore(
            @RequestBody(required = false) Map<String, String> body) {
        AccountDeletionService.RestoreResult res = accountDeletionService.restore(
                body == null ? null : body.get("token"),
                body == null ? null : body.get("email"),
                body == null ? null : body.get("password"));
        return ResponseEntity.ok(ApiResponse.success(Map.of("restored", res.restored())));
    }

    /**
     * @deprecated Legacy bearer-only immediate delete — UNSAFE under the current
     * policy ("a bearer token alone can never initiate deletion"). Now delegates to
     * the re-auth grace flow ({@link #requestDeletion}); kept so existing clients do
     * not 404. New clients must use {@code POST /account/delete}.
     */
    @Deprecated
    @DeleteMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteAccount(
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) Map<String, String> body) {
        return requestDeletion(userId, body);
    }
}
