package com.pally.api.consent;

import com.pally.domain.consent.ConsentService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PDPA parental consent endpoints.
 *
 * <p>Flow for under-13:
 * 1. Child calls {@code POST /consent/request-parent} with a parent email.
 *    → creates consent_requests row, sends email, sets account to PENDING_CONSENT.
 * 2. Parent opens the one-tap link → calls {@code POST /consent/approve?token=…}.
 *    → marks request APPROVED, flips child account to ACTIVE, records consent.
 * 3. Child polls {@code GET /consent/status} — when APPROVED the app unlocks.
 *
 * <p>Flow for 13+:
 * 1. Child calls {@code POST /consent/self} with their acceptance.
 *    → records consent, account already ACTIVE (no gate).
 */
@RestController
@RequestMapping("/api/v1/consent")
@RequiredArgsConstructor
@Slf4j
public class ConsentController {

    private final ConsentService consentService;

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(consentService.getStatus(userId)));
    }

    @PostMapping("/request-parent")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestParent(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String parentEmail = body == null ? null : body.get("parentEmail");
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(consentService.requestParentConsent(userId, parentEmail)));
    }

    @PostMapping("/resend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> resend(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                ApiResponse.created(consentService.resendParentConsent(userId)));
    }

    @PostMapping("/approve")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(
            @RequestParam String token) {
        return ResponseEntity.ok(ApiResponse.success(
                consentService.approveParentConsent(token)));
    }

    @PostMapping("/self")
    public ResponseEntity<ApiResponse<Map<String, Object>>> selfConsent(
            @AuthenticationPrincipal String userId) {
        consentService.recordSelfConsent(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "CONSENTED")));
    }

    @PostMapping("/family-accept")
    public ResponseEntity<ApiResponse<Map<String, Object>>> familyAcceptConsent(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String parentId = body == null ? null : body.get("parentId");
        consentService.recordFamilyAcceptConsent(userId, parentId);
        return ResponseEntity.ok(ApiResponse.success(
                Map.of("status", "FAMILY_CONSENT_GRANTED")));
    }

    @PostMapping("/ai-data-transfer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> grantAiDataTransferConsent(
            @AuthenticationPrincipal String userId) {
        consentService.recordAiDataTransferConsent(userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", "AI_CONSENT_GRANTED",
                "reason", "AI_DATA_TRANSFER"
        )));
    }
}
