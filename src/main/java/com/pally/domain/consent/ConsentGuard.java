package com.pally.domain.consent;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.ProfileCompletionRequiredException;
import com.pally.shared.exception.ConsentRequiredException;
import com.pally.shared.exception.GuardianRequiredException;
import com.pally.shared.exception.ParentalConsentPendingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Single gate for PDPA / PDPC consent enforcement.
 *
 * <p>Every use-case or controller that persists a child's personal data or sends
 * it to a third-party AI processor calls into this guard before proceeding. This
 * mirrors exactly how PremiumService (freemium cap) works — one injectable guard,
 * consistent semantics, testable in isolation.
 *
 * <p>Three independent gates live here:
 * <ul>
 *   <li>{@link #requireActive(String, String)} — account-status gate
 *       (PENDING_CONSENT → 403 CONSENT_REQUIRED).</li>
 *   <li>{@link #requireAiConsent(String)} — third-party AI disclosure gate,
 *       ALWAYS enforced (403 AI_CONSENT_REQUIRED until granted).</li>
 *   <li>{@link #requireGuardianIfUnder13(String)} — PDPC 2024 age gate: an
 *       under-13 user must have a parent linked + consented
 *       (403 PARENT_LINK_REQUIRED until then). A no-op for 13+ users.</li>
 * </ul>
 *
 * <p>Safety (moderation, self-harm handling) is NEVER gated — only
 * personal-data generation is restricted.
 */
@Service
@Slf4j
public class ConsentGuard {

    public static final String STATUS_ACTIVE  = "ACTIVE";
    public static final String STATUS_PENDING = "PENDING_CONSENT";
    /// Social sign-in that hasn't completed the profile (birth year) step yet.
    public static final String STATUS_PENDING_PROFILE = "PENDING_PROFILE";

    /// The ALLOW-LIST of active-equivalent statuses. requireActive() FAILS CLOSED:
    /// a status not in here is blocked. This replaces per-guard enumeration of blocking
    /// states — a new blocking state (PENDING_PROFILE) is simply "not on the allow-list",
    /// and a fabricated/unknown status can never slip through as "active".
    private static final java.util.Set<String> ACTIVE_STATUSES =
            java.util.Set.of(STATUS_ACTIVE);

    /// Reason code for third-party AI data transfer consent (Apple 5.1.2 / PDPA overseas transfer).
    /// Use with {@link #requireAiConsent(String)}.
    public static final String REASON_AI_DATA_TRANSFER = "AI_DATA_TRANSFER";

    /// Reason code for the under-13 guardian gate (PDPC 2024).
    /// Use with {@link #requireGuardianIfUnder13(String)}.
    public static final String REASON_PARENT_LINK_REQUIRED = "PARENT_LINK_REQUIRED";

    /// Reason code for the DEFAULT-DENY case: age is not on file, so we cannot
    /// establish the user is 13+. The client must collect a declared age before any
    /// new-child-data action proceeds — a child must not bypass by omitting it.
    public static final String REASON_AGE_DECLARATION_REQUIRED = "AGE_DECLARATION_REQUIRED";

    private final UserRepository userRepo;
    private final ConsentRecordJpaRepository consentRecordRepo;
    private final UserAgeService userAgeService;
    private final com.pally.domain.consent.ConsentRepository consentRepository;
    private final ConsentService consentService;
    private final OrgStaffJpaRepository staffRepo;
    private final OrganizationJpaRepository orgRepo;

    public ConsentGuard(UserRepository userRepo,
                        ConsentRecordJpaRepository consentRecordRepo,
                        UserAgeService userAgeService,
                        com.pally.domain.consent.ConsentRepository consentRepository,
                        ConsentService consentService,
                        OrgStaffJpaRepository staffRepo,
                        OrganizationJpaRepository orgRepo) {
        this.userRepo = userRepo;
        this.consentRecordRepo = consentRecordRepo;
        this.userAgeService = userAgeService;
        this.consentRepository = consentRepository;
        this.consentService = consentService;
        this.staffRepo = staffRepo;
        this.orgRepo = orgRepo;
    }

    /// Build the rich PARENTAL_CONSENT_PENDING exception (masked email + resend) for a
    /// blocked under-13. The single place a "waiting for parent" block is constructed.
    private ParentalConsentPendingException parentalConsentPending(String userId) {
        ConsentService.ResendInfo info = consentService.resendInfo(userId);
        return new ParentalConsentPendingException(
                info.maskedParentEmail(), info.resendAvailable(), info.resendAvailableInSeconds());
    }

    /**
     * FAIL-CLOSED account-status gate. A user may proceed only if their status is on the
     * {@link #ACTIVE_STATUSES} allow-list; every other status blocks. PENDING_CONSENT
     * gets the rich parental-consent block; PENDING_PROFILE gets a profile-required
     * block; anything unrecognized (including a fabricated value) is denied generically.
     *
     * @param userId the authenticated user
     * @param reason short code describing the gated action (e.g. "UPLOAD")
     */
    public void requireActive(String userId, String reason) {
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) {
            // Fail-closed: an authenticated action whose user row is gone is not active.
            throw new BusinessException("Account is not active", 403);
        }
        String status = userOpt.get().getAccountStatus() != null
                ? userOpt.get().getAccountStatus() : STATUS_ACTIVE;

        if (ACTIVE_STATUSES.contains(status)) return; // the only allowed path

        if (STATUS_PENDING.equals(status)) {
            log.info("[Consent] PENDING_CONSENT user={} tried gated action={}", userId, reason);
            // Rich PARENTAL_CONSENT_PENDING (masked email + resend) so the client shows
            // the "waiting for your parent" panel consistently on every gated path.
            throw parentalConsentPending(userId);
        }
        if (STATUS_PENDING_PROFILE.equals(status)) {
            log.info("[Consent] PENDING_PROFILE user={} tried gated action={}", userId, reason);
            throw new ProfileCompletionRequiredException();
        }
        // Any other (unrecognized/fabricated) status — fail closed.
        log.warn("[Consent] non-active status='{}' user={} BLOCKED action={}", status, userId, reason);
        throw new BusinessException("Account is not active", 403);
    }

    /**
     * Returns true when the account is PENDING_CONSENT.
     * Use this for conditional paths that don't hard-block (e.g. skip chat persist).
     */
    public boolean isPending(String userId) {
        return userRepo.findById(userId)
                .map(u -> STATUS_PENDING.equals(u.getAccountStatus()))
                .orElse(false);
    }

    /**
     * Checks that an <em>under-13</em> user has granted AI data-transfer consent
     * before sending their data to third-party AI processors (Anthropic Claude,
     * Google Gemini). Users aged 13 and over self-consent and are never blocked here.
     *
     * <p>For under-13 users: the plain-language disclosure naming Anthropic (Claude)
     * and Google (Gemini) as overseas AI processors must be shown in the UI BEFORE
     * the user's first upload or chat; the client then POSTs
     * {@code /consent/ai-data-transfer} which records the consent that satisfies this
     * gate. Until then this throws {@link AiConsentRequiredException} with reason
     * {@link #REASON_AI_DATA_TRANSFER}, mapped to HTTP 403 / code AI_CONSENT_REQUIRED.
     *
     * @param userId the authenticated user
     */
    public void requireAiConsent(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            // FAIL-CLOSED: an authenticated AI action whose user row is missing cannot be
            // established as 13+/consented. (A valid token implies a real user, so this is
            // an anomaly — deny rather than ship a child's data to a model.)
            throw new BusinessException("Account is not active", 403);
        }
        if (!userAgeService.isUnder13(user)) return; // 13+ → self-consent, no gate

        boolean hasConsented = consentRecordRepo.findAll().stream()
                .anyMatch(r -> userId.equals(r.getUserId())
                        && r.getPurposes() != null
                        && r.getPurposes().contains(REASON_AI_DATA_TRANSFER));

        if (!hasConsented) {
            log.info("[Consent] AI_DATA_TRANSFER consent required for under-13 user={}", userId);
            throw new AiConsentRequiredException(REASON_AI_DATA_TRANSFER);
        }
    }

    /**
     * Returns true when the user is a centre operator: an active org staff member
     * (has an OrgStaffJpaEntity row) OR the owner of any organisation (identified
     * via org.ownerUserId — CentreService.onboard sets this but never writes an
     * OrgStaff row for the owner).
     *
     * <p>Both roles are adult principals. No child-data or age gate applies.
     *
     * <p><strong>Call order contract for every new gate method:</strong>
     * <pre>
     *   if (isCentreOperator(userId)) return; // adult operator, no child gates apply
     * </pre>
     */
    boolean isCentreOperator(String userId) {  // package-private: callable from unit tests
        if (staffRepo.existsByUserIdAndStatus(userId, OrgStaffJpaEntity.STATUS_ACTIVE)) {
            return true;
        }
        return orgRepo.existsByOwnerUserId(userId);
    }

    /**
     * SINGLE eligibility for ingesting NEW child-authored data (note upload, free AI
     * chat, photo-question — anything that ships the child's text/image to a model or
     * stores it). DEFAULT-DENY, computed in ONE place so a new ingress can't quietly
     * reintroduce the fail-open default:
     * <pre>canIngestChildData = ageEstablished &amp;&amp; (is13Plus || parentApproved)</pre>
     * Gated on consent STATE, never on a null age (unknown ⇒ "we never asked" ⇒ not
     * cleared ⇒ blocked), and never on {@code parentId} alone. Reading pre-generated
     * centre content is NOT ingress and is never gated by this.
     */
    public boolean canIngestChildData(User user) {
        if (user == null) {
            return false; // FAIL-CLOSED: no user row → age never established → not cleared
        }
        // Centre operators (owner or active staff) are adult principals — bypass.
        if (isCentreOperator(user.getId())) {
            return true;
        }
        if (user.getBirthYear() == null) {
            return false; // unknown age → cannot establish 13+ → not cleared
        }
        if (!userAgeService.isUnder13(user)) {
            return true; // established 13+ → self-consent
        }
        // Under-13: PDPA requires actual, VERIFIED parental CONSENT (the email
        // approval) before the child's data is processed / sent overseas to a
        // model — NOT merely a claimed parent link. A parent claiming the link
        // code is neither consent nor verification, so parentId alone must NOT
        // clear ingress (that contradicted this method's own javadoc). Require the
        // APPROVED consent record; a PENDING child gets the consent-pending path.
        return consentRepository
                .findLatestRequestByChildUserIdAndStatus(
                        user.getId(), ConsentRepository.ConsentRequest.STATUS_APPROVED)
                .isPresent();
    }

    /**
     * THE single guard every child-data ingress calls at the ENTRY of its handler —
     * before any Gemini/Claude call and before any DB write. Throws when
     * {@link #canIngestChildData} is false: a blocked under-13 awaiting approval →
     * {@link ParentalConsentPendingException} (code PARENTAL_CONSENT_PENDING, masked
     * email + resend); unknown age → {@link GuardianRequiredException}
     * (AGE_DECLARATION_REQUIRED). This REPLACES the removed fail-open
     * requireGuardianIfUnder13 — there is no weak variant left to call.
     */
    public void requireChildDataIngressConsent(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            // FAIL-CLOSED: authenticated ingress with a missing user row is not cleared.
            throw new GuardianRequiredException(REASON_AGE_DECLARATION_REQUIRED);
        }
        // Centre operators (owner or active staff) are adult principals — bypass.
        if (isCentreOperator(userId)) {
            return;
        }
        if (canIngestChildData(user)) {
            return;
        }
        if (user.getBirthYear() == null) {
            log.info("[Consent] child-data blocked user={} — age not declared (default-deny)", userId);
            throw new GuardianRequiredException(REASON_AGE_DECLARATION_REQUIRED);
        }
        log.info("[Consent] child-data blocked user={} — awaiting parental consent", userId);
        throw parentalConsentPending(userId);
    }

    /**
     * AI third-party transfer gate for AI-producing paths that are NOT child-data ingress
     * (wiki compile, module/quiz generation, teach-Mochi). The under-13 ingress
     * gate now lives in {@link #requireChildDataIngressConsent}; this is purely the
     * AI-disclosure consent (Apple 5.1.2 / PDPA overseas transfer).
     */
    public void requireAiAllowed(String userId) {
        requireAiConsent(userId);
    }
}
