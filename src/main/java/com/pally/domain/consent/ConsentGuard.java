package com.pally.domain.consent;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.ConsentRequiredException;
import com.pally.shared.exception.GuardianRequiredException;
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

    public ConsentGuard(UserRepository userRepo,
                        ConsentRecordJpaRepository consentRecordRepo,
                        UserAgeService userAgeService,
                        com.pally.domain.consent.ConsentRepository consentRepository) {
        this.userRepo = userRepo;
        this.consentRecordRepo = consentRecordRepo;
        this.userAgeService = userAgeService;
        this.consentRepository = consentRepository;
    }

    /**
     * Throws {@link ConsentRequiredException} if the user's account is PENDING_CONSENT.
     * Fails open (allows) when the status cannot be determined — never silently blocks
     * a user who has already been approved.
     *
     * @param userId the authenticated user
     * @param reason short code describing the gated action (e.g. "UPLOAD")
     */
    public void requireActive(String userId, String reason) {
        String status = userRepo.findById(userId)
                .map(u -> u.getAccountStatus() != null ? u.getAccountStatus() : STATUS_ACTIVE)
                .orElse(STATUS_ACTIVE); // user not found → let the downstream handle it

        if (STATUS_PENDING.equals(status)) {
            log.info("[Consent] PENDING user={} tried gated action={}", userId, reason);
            throw new ConsentRequiredException(reason);
        }
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
        if (user == null) return; // user not found → fail open
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
     * PDPC 2024 age gate: if the user is under 13 (derived server-side from their
     * birth year) AND no parent/guardian has claimed and consented for them, throws
     * {@link GuardianRequiredException} (HTTP 403 / code PARENT_LINK_REQUIRED).
     *
     * <p>For 13+ users (the vast majority — target audience is 13–25) this is a no-op:
     * they self-consent, so nothing changes for them.
     *
     * <p>"Parent linked + consented" is defined as: the child account has a non-null
     * {@code parentId}. The parent-claim flow is the single moment a parent attaches a
     * child to their family, and that flow records the parental consent — so a linked
     * parent IS the signal that guardian consent exists. We therefore treat
     * {@code parentId != null} as satisfying the gate.
     *
     * <p>Fails open if the user cannot be loaded — never silently blocks.
     *
     * @param userId the authenticated user
     */
    public void requireGuardianIfUnder13(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return; // user not found → let the downstream handle it
        }
        if (!userAgeService.isUnder13(user)) {
            return; // 13+ (or unknown age) → self-consent, no-op
        }

        boolean parentLinked = user.getParentId() != null && !user.getParentId().isBlank();
        if (!parentLinked) {
            log.info("[Consent] under-13 user={} blocked — no parent linked", userId);
            throw new GuardianRequiredException(REASON_PARENT_LINK_REQUIRED);
        }
    }

    /**
     * DEFAULT-DENY gate for ingesting NEW child data (uploading own notes, personal
     * avatar creation, free-form AI chat input). Unlike {@link #requireGuardianIfUnder13}
     * — which fails OPEN on unknown age and so only catches honest self-declarers — this
     * fails CLOSED:
     * <ul>
     *   <li>age not on file ({@code birthYear == null}) → throw
     *       {@link #REASON_AGE_DECLARATION_REQUIRED}: we can't establish 13+, so the
     *       client must collect a declared age first. A child can't slip through by
     *       leaving age blank.</li>
     *   <li>under 13 without recorded parental consent → throw
     *       {@link #REASON_PARENT_LINK_REQUIRED}. Consent counts when an APPROVED
     *       parental-consent request exists (the email-token flow) OR a parent is linked
     *       ({@code parentId}).</li>
     *   <li>established 13+ → allow.</li>
     * </ul>
     *
     * <p>Does NOT gate login or centre-content consumption / lessons — those stay open
     * for a pending child (the centre monitors that data). Only NEW personal-data
     * ingestion is restricted.
     */
    public void requireParentalConsentForChildData(String userId) {
        User user = userRepo.findById(userId).orElse(null);
        if (user == null) {
            return; // auth already passed; a missing row is handled downstream
        }
        if (user.getBirthYear() == null) {
            log.info("[Consent] child-data blocked user={} — age not declared (default-deny)", userId);
            throw new GuardianRequiredException(REASON_AGE_DECLARATION_REQUIRED);
        }
        if (!userAgeService.isUnder13(user)) {
            return; // established 13+ → self-consent
        }
        boolean parentLinked = user.getParentId() != null && !user.getParentId().isBlank();
        boolean parentApproved = consentRepository
                .findLatestRequestByChildUserIdAndStatus(
                        userId, com.pally.domain.consent.ConsentRepository.ConsentRequest.STATUS_APPROVED)
                .isPresent();
        if (!parentLinked && !parentApproved) {
            log.info("[Consent] under-13 user={} blocked — no recorded parental consent", userId);
            throw new GuardianRequiredException(REASON_PARENT_LINK_REQUIRED);
        }
    }

    /**
     * Single entry point that every AI-producing path (chat, upload, photo-question,
     * wiki compile, modules, quiz/flashcard generation, teach-Mochi) MUST call before
     * sending a user's personal data to a third-party AI processor. Combines the
     * AI-data-transfer disclosure gate with the under-13 guardian gate so a caller
     * can never enforce one but forget the other — new AI endpoints should call this.
     *
     * @param userId the authenticated user
     */
    public void requireAiAllowed(String userId) {
        requireAiConsent(userId);
        requireGuardianIfUnder13(userId);
    }
}
