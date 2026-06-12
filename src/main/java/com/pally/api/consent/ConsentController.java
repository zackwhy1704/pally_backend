package com.pally.api.consent;

import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaEntity;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.consent.ConsentRequestJpaEntity;
import com.pally.infrastructure.persistence.consent.ConsentRequestJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

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

    private static final String POLICY_VERSION = "2025-06-01";
    private static final String PURPOSES_JSON =
            "[\"tutoring\",\"quiz\",\"progress\",\"parent_visibility\"]";

    private final ConsentRequestJpaRepository requestRepo;
    private final ConsentRecordJpaRepository  recordRepo;
    private final UserJpaRepository           userRepo;
    private final PremiumService              premiumService;

    /// Current status for the authenticated child.
    /// Returns: accountStatus, latestRequest (if any), approvedAt.
    @GetMapping("/status")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("accountStatus", user.getAccountStatus());
        // Whether the user has granted third-party AI data-transfer consent.
        // The Flutter client reads this to decide whether to show the AI
        // disclosure screen before the first upload/chat.
        boolean aiDataTransfer = recordRepo.findAll().stream()
                .anyMatch(r -> userId.equals(r.getUserId())
                        && r.getPurposes() != null
                        && r.getPurposes().contains(ConsentGuard.REASON_AI_DATA_TRANSFER));
        body.put("aiDataTransfer", aiDataTransfer);
        requestRepo.findFirstByChildUserIdAndStatusOrderByCreatedAtDesc(
                userId, ConsentRequestJpaEntity.STATUS_PENDING)
                .ifPresent(r -> {
                    body.put("pendingRequest", Map.of(
                            "parentEmail", r.getParentEmail(),
                            "expiresAt", r.getExpiresAt().toString()
                    ));
                });
        requestRepo.findFirstByChildUserIdAndStatusOrderByCreatedAtDesc(
                userId, ConsentRequestJpaEntity.STATUS_APPROVED)
                .ifPresent(r -> body.put("approvedAt",
                        r.getApprovedAt() != null ? r.getApprovedAt().toString() : null));
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /// Under-13: send a parental consent request by email.
    @PostMapping("/request-parent")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestParent(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String parentEmail = body == null ? null : body.get("parentEmail");
        if (parentEmail == null || parentEmail.isBlank()
                || !parentEmail.contains("@")) {
            throw new BusinessException("A valid parent email is required", 400);
        }
        parentEmail = parentEmail.trim().toLowerCase();

        // Mark account PENDING_CONSENT (idempotent)
        userRepo.findById(userId).ifPresent(u -> {
            u.setAccountStatus(ConsentGuard.STATUS_PENDING);
            userRepo.save(u);
        });

        // Create/refresh the consent request
        String token = UUID.randomUUID().toString().replace("-", "");
        ConsentRequestJpaEntity req = new ConsentRequestJpaEntity();
        req.setId(IdGenerator.newId());
        req.setChildUserId(userId);
        req.setParentEmail(parentEmail);
        req.setToken(token);
        req.setStatus(ConsentRequestJpaEntity.STATUS_PENDING);
        req.setCreatedAt(Instant.now());
        req.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        requestRepo.save(req);

        // TODO: wire real email sending here (reuse the email-verification mail path)
        // EmailService.sendConsentEmail(parentEmail, childName, token, approvalUrl);
        log.info("[Consent] Consent request created user={} parentEmail={} token={}",
                userId, parentEmail, token);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(Map.of(
                "status", "PENDING",
                "parentEmail", parentEmail,
                "expiresAt", req.getExpiresAt().toString()
        )));
    }

    /// Resend the consent email (same token if still valid, otherwise create new).
    @PostMapping("/resend")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> resend(
            @AuthenticationPrincipal String userId) {
        return requestParent(userId, Map.of("parentEmail",
                requestRepo.findFirstByChildUserIdAndStatusOrderByCreatedAtDesc(
                                userId, ConsentRequestJpaEntity.STATUS_PENDING)
                        .map(ConsentRequestJpaEntity::getParentEmail)
                        .orElseThrow(() -> new BusinessException(
                                "No pending consent request found", 404))));
    }

    /// Parent one-tap approval link (no auth required — token is the credential).
    @PostMapping("/approve")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> approve(
            @RequestParam String token) {
        ConsentRequestJpaEntity req = requestRepo.findByToken(token)
                .orElseThrow(() -> new BusinessException("Invalid or expired consent link", 404));

        if (!ConsentRequestJpaEntity.STATUS_PENDING.equals(req.getStatus())) {
            throw new BusinessException("This consent link has already been used", 400);
        }
        if (req.getExpiresAt().isBefore(Instant.now())) {
            req.setStatus(ConsentRequestJpaEntity.STATUS_EXPIRED);
            requestRepo.save(req);
            throw new BusinessException("This consent link has expired — ask your child to request a new one", 400);
        }

        // Approve
        req.setStatus(ConsentRequestJpaEntity.STATUS_APPROVED);
        req.setApprovedAt(Instant.now());
        requestRepo.save(req);

        // Flip child account to ACTIVE
        userRepo.findById(req.getChildUserId()).ifPresent(u -> {
            u.setAccountStatus(ConsentGuard.STATUS_ACTIVE);
            userRepo.save(u);
        });
        // Start the 7-day trial NOW that the account is ACTIVE — not at
        // registration (when it was PENDING), so the week isn't burned
        // waiting for the parent to approve.
        premiumService.grantTrial(req.getChildUserId());

        // Record in audit log
        ConsentRecordJpaEntity record = new ConsentRecordJpaEntity();
        record.setId(IdGenerator.newId());
        record.setUserId(req.getChildUserId());
        record.setConsenter("PARENT");
        record.setMethod("EMAIL_LINK");
        record.setPurposes(PURPOSES_JSON);
        record.setPolicyVersion(POLICY_VERSION);
        record.setCreatedAt(Instant.now());
        recordRepo.save(record);

        log.info("[Consent] APPROVED child={} by parent email={}",
                req.getChildUserId(), req.getParentEmail());

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", "APPROVED",
                "childUserId", req.getChildUserId()
        )));
    }

    /// 13–17: child self-consent (already ACTIVE; this records the consent for audit).
    @PostMapping("/self")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> selfConsent(
            @AuthenticationPrincipal String userId) {
        ConsentRecordJpaEntity record = new ConsentRecordJpaEntity();
        record.setId(IdGenerator.newId());
        record.setUserId(userId);
        record.setConsenter("SELF");
        record.setMethod("CHECKBOX");
        record.setPurposes(PURPOSES_JSON);
        record.setPolicyVersion(POLICY_VERSION);
        record.setCreatedAt(Instant.now());
        recordRepo.save(record);

        log.info("[Consent] Self-consent recorded user={}", userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "CONSENTED")));
    }

    /**
     * Child accepts parent data visibility — a PDPA consent record that
     * grants the linked parent access to view the child's learning data.
     */
    @PostMapping("/family-accept")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> familyAcceptConsent(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String parentId = body == null ? null : body.get("parentId");
        if (parentId == null || parentId.isBlank()) {
            throw new BusinessException("parentId is required", 400);
        }
        // Verify the parent exists and is the child's actual parent
        UserJpaEntity child = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (!parentId.equals(child.getParentId())) {
            throw new BusinessException("Specified parentId is not your parent", 403);
        }

        ConsentRecordJpaEntity record = new ConsentRecordJpaEntity();
        record.setId(IdGenerator.newId());
        record.setUserId(userId);
        record.setConsenter("SELF");
        record.setMethod("CHECKBOX");
        record.setPurposes("[\"parent_visibility\"]");
        record.setPolicyVersion(POLICY_VERSION);
        record.setCreatedAt(Instant.now());
        record.setChildId(userId);
        record.setParentId(parentId);
        recordRepo.save(record);

        log.info("[Consent] Family visibility consent granted child={} parent={}",
                userId, parentId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", "FAMILY_CONSENT_GRANTED")));
    }

    /**
     * Records the user's explicit consent to their notes and chat messages being
     * sent to third-party overseas AI processors (Anthropic Claude, Google Gemini)
     * for the purpose of answering homework questions.
     *
     * <p>Required by Apple App Store guideline 5.1.2 and PDPA overseas transfer rules.
     * The frontend must show a plain-language disclosure BEFORE calling this endpoint.
     *
     * <p>Recording this grant satisfies the always-on AI-disclosure gate
     * ({@code ConsentGuard.requireAiConsent}); without it, chat and upload return
     * 403 AI_CONSENT_REQUIRED. The frontend must show the plain-language disclosure
     * naming Anthropic (Claude) and Google (Gemini) as overseas AI processors and the
     * purpose (answering homework questions) BEFORE calling this endpoint — i.e.
     * before the user's first upload or chat message.
     */
    @PostMapping("/ai-data-transfer")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> grantAiDataTransferConsent(
            @AuthenticationPrincipal String userId) {
        ConsentRecordJpaEntity record = new ConsentRecordJpaEntity();
        record.setId(IdGenerator.newId());
        record.setUserId(userId);
        record.setConsenter("SELF");
        // Must fit consent_records.method VARCHAR(20). "AI_TRANSFER_DISCLOSURE"
        // (21 chars) overflowed and 500'd once the gate became always-on.
        record.setMethod("AI_DISCLOSURE");
        record.setPurposes("[\"AI_DATA_TRANSFER\",\"tutoring\",\"quiz\"]");
        record.setPolicyVersion(POLICY_VERSION);
        record.setCreatedAt(Instant.now());
        recordRepo.save(record);

        log.info("[Consent] AI_DATA_TRANSFER consent granted user={}", userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "status", "AI_CONSENT_GRANTED",
                "reason", "AI_DATA_TRANSFER"
        )));
    }
}
