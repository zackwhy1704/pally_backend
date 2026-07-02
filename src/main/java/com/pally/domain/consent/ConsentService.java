package com.pally.domain.consent;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.push.FcmService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns all PDPA consent record / request creation and lookup logic.
 *
 * <p>Three flows are supported:
 * <ul>
 *   <li>Under-13 parental consent — {@link #getStatus}, {@link #requestParentConsent},
 *       {@link #approveParentConsent}.</li>
 *   <li>13–17 self-consent — {@link #recordSelfConsent}.</li>
 *   <li>Family visibility consent — {@link #recordFamilyAcceptConsent}.</li>
 *   <li>AI data-transfer consent — {@link #recordAiDataTransferConsent}.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentService {

    private static final String POLICY_VERSION = "2025-06-01";
    private static final String PURPOSES_JSON =
            "[\"tutoring\",\"quiz\",\"progress\",\"parent_visibility\"]";

    private final ConsentRepository consentRepository;
    private final UserRepository    userRepository;
    private final PremiumService    premiumService;
    private final EmailService      emailService;
    private final FcmService        fcmService;

    @Value("${pally.web-base-url:https://apalchi.com}")
    private String webBaseUrl;

    /// Resend cooldown — prevents email spam and drives the client's disabled-button
    /// countdown. Surfaced both in the block envelope and the resend response.
    public static final int RESEND_COOLDOWN_SECONDS = 60;

    /// Display metadata for the "waiting for parent" state: a MASKED parent email
    /// (which inbox to check) + whether/when a resend is allowed. Never the full email.
    public record ResendInfo(String maskedParentEmail, boolean resendAvailable,
                             long resendAvailableInSeconds) {}

    /// Resend availability for the child's latest PENDING request. No request → resend
    /// is available (request-parent will create one). Masks the parent email.
    @Transactional(readOnly = true)
    public ResendInfo resendInfo(String userId) {
        return consentRepository.findLatestRequestByChildUserIdAndStatus(
                        userId, ConsentRepository.ConsentRequest.STATUS_PENDING)
                .map(r -> {
                    long elapsed = java.time.Duration.between(r.createdAt(), Instant.now()).getSeconds();
                    long remaining = Math.max(0, RESEND_COOLDOWN_SECONDS - elapsed);
                    return new ResendInfo(maskEmail(r.parentEmail()), remaining == 0, remaining);
                })
                .orElse(new ResendInfo(null, true, 0));
    }

    /// Masks an email for display: first character + "***" + domain, e.g.
    /// {@code john@gmail.com → j***@gmail.com}. Never leaks the full address.
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Returns a status map for the authenticated child:
     * {@code accountStatus}, {@code aiDataTransfer}, and optionally
     * {@code pendingRequest} / {@code approvedAt}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        Map<String, Object> body = new HashMap<>();
        body.put("accountStatus", user.getAccountStatus());
        body.put("aiDataTransfer", consentRepository.hasConsentForPurpose(
                userId, ConsentGuard.REASON_AI_DATA_TRANSFER));

        consentRepository.findLatestRequestByChildUserIdAndStatus(
                        userId, ConsentRepository.ConsentRequest.STATUS_PENDING)
                .ifPresent(r -> body.put("pendingRequest", Map.of(
                        "parentEmail", r.parentEmail(),
                        "expiresAt", r.expiresAt().toString()
                )));

        consentRepository.findLatestRequestByChildUserIdAndStatus(
                        userId, ConsentRepository.ConsentRequest.STATUS_APPROVED)
                .ifPresent(r -> body.put("approvedAt",
                        r.approvedAt() != null ? r.approvedAt().toString() : null));

        return body;
    }

    // ── Under-13 parental consent ─────────────────────────────────────────────

    /**
     * Creates a consent request for the child and marks the account PENDING_CONSENT.
     * Returns a summary map: {@code status, parentEmail, expiresAt}.
     */
    @Transactional
    public Map<String, Object> requestParentConsent(String userId, String parentEmail) {
        if (parentEmail == null || parentEmail.isBlank() || !parentEmail.contains("@")) {
            throw new BusinessException("A valid parent email is required", 400);
        }
        String email = parentEmail.trim().toLowerCase();

        // Mark account PENDING_CONSENT (idempotent)
        userRepository.findById(userId).ifPresent(u -> {
            u.setAccountStatus(ConsentGuard.STATUS_PENDING);
            userRepository.save(u);
        });

        String token = UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        ConsentRepository.ConsentRequest req = new ConsentRepository.ConsentRequest(
                IdGenerator.newId(), userId, email, token,
                ConsentRepository.ConsentRequest.STATUS_PENDING,
                now, now.plus(7, ChronoUnit.DAYS), null
        );
        consentRepository.saveRequest(req);

        log.info("[Consent] Consent request created user={} parentEmail={} token={}",
                userId, email, token);
        sendConsentEmail(email, token);

        return Map.of(
                "status", "PENDING",
                "parentEmail", email,
                "expiresAt", req.expiresAt().toString()
        );
    }

    /**
     * Resends the consent email by reusing the existing pending request's email.
     */
    @Transactional
    public Map<String, Object> resendParentConsent(String userId) {
        ResendInfo info = resendInfo(userId);
        if (!info.resendAvailable()) {
            // 429: cooldown active — the client shows the countdown from this value.
            throw new BusinessException(
                    "Please wait " + info.resendAvailableInSeconds()
                    + "s before resending the approval email.", 429);
        }
        String parentEmail = consentRepository
                .findLatestRequestByChildUserIdAndStatus(
                        userId, ConsentRepository.ConsentRequest.STATUS_PENDING)
                .map(ConsentRepository.ConsentRequest::parentEmail)
                .orElseThrow(() -> new BusinessException(
                        "No pending consent request found", 404));
        // Re-issues a FRESH token (the old one may have expired) + re-emails.
        Map<String, Object> result = new HashMap<>(requestParentConsent(userId, parentEmail));
        result.put("parentEmailMasked", maskEmail(parentEmail));
        result.put("resendAvailableInSeconds", (long) RESEND_COOLDOWN_SECONDS);
        return result;
    }

    /**
     * Approves a parental consent request via its one-tap token.
     * Flips the child's account to ACTIVE, grants the 7-day trial, and
     * records an audit consent record.
     *
     * @return summary map: {@code status, childUserId}.
     */
    @Transactional
    public Map<String, Object> approveParentConsent(String token) {
        ConsentRepository.ConsentRequest req = consentRepository
                .findRequestByToken(token)
                .orElseThrow(() -> new BusinessException(
                        "Invalid or expired consent link", 404));

        if (!ConsentRepository.ConsentRequest.STATUS_PENDING.equals(req.status())) {
            throw new BusinessException("This consent link has already been used", 400);
        }
        if (req.expiresAt().isBefore(Instant.now())) {
            // Expire the request
            consentRepository.saveRequest(new ConsentRepository.ConsentRequest(
                    req.id(), req.childUserId(), req.parentEmail(), req.token(),
                    ConsentRepository.ConsentRequest.STATUS_EXPIRED,
                    req.createdAt(), req.expiresAt(), null
            ));
            throw new BusinessException(
                    "This consent link has expired — ask your child to request a new one", 400);
        }

        // Approve
        consentRepository.saveRequest(new ConsentRepository.ConsentRequest(
                req.id(), req.childUserId(), req.parentEmail(), req.token(),
                ConsentRepository.ConsentRequest.STATUS_APPROVED,
                req.createdAt(), req.expiresAt(), Instant.now()
        ));

        // Flip child account to ACTIVE
        userRepository.findById(req.childUserId()).ifPresent(u -> {
            u.setAccountStatus(ConsentGuard.STATUS_ACTIVE);
            userRepository.save(u);
        });

        // Grant the 7-day trial now that the account is ACTIVE
        premiumService.grantTrial(req.childUserId());

        // Record in audit log
        consentRepository.saveRecord(new ConsentRepository.ConsentRecord(
                IdGenerator.newId(), req.childUserId(),
                "PARENT", "EMAIL_LINK",
                PURPOSES_JSON, POLICY_VERSION,
                Instant.now(), null, null
        ));

        log.info("[Consent] APPROVED child={} by parent email={}",
                req.childUserId(), req.parentEmail());

        // Event-driven unlock (primary trigger): push the child so the app
        // re-checks consent and unlocks without a restart. Best-effort — a push
        // failure (no token / denied notifs / FCM down) must NEVER break the
        // approval; the child app's resume-check is the reliability backbone.
        try {
            fcmService.sendToUser(
                    req.childUserId(),
                    "You're approved! 🎉",
                    "Your parent said yes — tap to start learning.",
                    Map.of("type", "PARENTAL_CONSENT_APPROVED"));
        } catch (Exception e) {
            log.warn("[Consent] approval push failed child={} (non-fatal): {}",
                    req.childUserId(), e.getMessage());
        }

        return Map.of("status", "APPROVED", "childUserId", req.childUserId());
    }

    private void sendConsentEmail(String parentEmail, String token) {
        try {
            String approveUrl = webBaseUrl + "/consent/approve?token=" + token;
            String html = """
                    <div style="font-family:sans-serif;max-width:520px;margin:0 auto;color:#1F1733">
                      <h2 style="color:#7042ED">Your child wants to use Apalchi 📚</h2>
                      <p>Your child has signed up to <strong>Apalchi</strong>, an AI study tutor
                         that helps students learn from their own notes.</p>
                      <p>To activate their account, please click the button below to grant consent.
                         This link is valid for 7 days.</p>
                      <p style="margin:32px 0;text-align:center">
                        <a href="%s"
                           style="background:#7042ED;color:white;text-decoration:none;
                                  padding:14px 28px;border-radius:8px;font-weight:700;font-size:16px;
                                  display:inline-block">
                          ✅ Approve & Activate
                        </a>
                      </p>
                      <p style="color:#6B618A;font-size:13px">
                        If you did not expect this email, you can safely ignore it — no account
                        will be activated without your approval.
                      </p>
                      <hr style="border:none;border-top:1px solid #E0DAF0;margin:24px 0"/>
                      <p style="color:#A8A0BD;font-size:11px">
                        Apalchi &middot; apalchi.com &middot; Questions? Visit apalchi.com/support
                      </p>
                    </div>
                    """.formatted(approveUrl);

            emailService.sendHtml(parentEmail,
                    "Action required: approve your child's Apalchi account", html);
        } catch (Exception e) {
            log.warn("[Consent] Failed to send consent email to={}: {}", parentEmail, e.getMessage());
        }
    }

    // ── Self-consent ──────────────────────────────────────────────────────────

    /**
     * Records a 13–17 child's self-consent for audit purposes.
     */
    @Transactional
    public void recordSelfConsent(String userId) {
        consentRepository.saveRecord(new ConsentRepository.ConsentRecord(
                IdGenerator.newId(), userId,
                "SELF", "CHECKBOX",
                PURPOSES_JSON, POLICY_VERSION,
                Instant.now(), null, null
        ));
        log.info("[Consent] Self-consent recorded user={}", userId);
    }

    // ── Family visibility consent ─────────────────────────────────────────────

    /**
     * Records the child's consent granting the linked parent visibility into
     * their learning data.
     */
    @Transactional
    public void recordFamilyAcceptConsent(String userId, String parentId) {
        if (parentId == null || parentId.isBlank()) {
            throw new BusinessException("parentId is required", 400);
        }
        User child = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (!parentId.equals(child.getParentId())) {
            throw new BusinessException("Specified parentId is not your parent", 403);
        }

        consentRepository.saveRecord(new ConsentRepository.ConsentRecord(
                IdGenerator.newId(), userId,
                "SELF", "CHECKBOX",
                "[\"parent_visibility\"]", POLICY_VERSION,
                Instant.now(), userId, parentId
        ));

        log.info("[Consent] Family visibility consent granted child={} parent={}",
                userId, parentId);
    }

    // ── AI data-transfer consent ──────────────────────────────────────────────

    /**
     * Records the user's explicit consent to have their notes and chat messages
     * sent to third-party overseas AI processors (Anthropic Claude, Google Gemini).
     */
    @Transactional
    public void recordAiDataTransferConsent(String userId) {
        consentRepository.saveRecord(new ConsentRepository.ConsentRecord(
                IdGenerator.newId(), userId,
                "SELF", "AI_DISCLOSURE",
                "[\"AI_DATA_TRANSFER\",\"tutoring\",\"quiz\"]", POLICY_VERSION,
                Instant.now(), null, null
        ));
        log.info("[Consent] AI_DATA_TRANSFER consent granted user={}", userId);
    }
}
