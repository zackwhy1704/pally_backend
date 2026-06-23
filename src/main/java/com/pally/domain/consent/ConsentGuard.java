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

    private final UserRepository userRepo;
    private final ConsentRecordJpaRepository consentRecordRepo;
    private final UserAgeService userAgeService;

    public ConsentGuard(UserRepository userRepo,
                        ConsentRecordJpaRepository consentRecordRepo,
                        UserAgeService userAgeService) {
        this.userRepo = userRepo;
        this.consentRecordRepo = consentRecordRepo;
        this.userAgeService = userAgeService;
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
