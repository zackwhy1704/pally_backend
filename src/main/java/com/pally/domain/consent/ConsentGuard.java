package com.pally.domain.consent;

import com.pally.infrastructure.persistence.consent.ConsentRecordJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.ConsentRequiredException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Single gate for PDPA consent enforcement.
 *
 * <p>Every use-case or controller that persists a child's personal data calls
 * {@link #requireActive(String, String)} before proceeding. This mirrors
 * exactly how PremiumService (freemium cap) works — one injectable guard,
 * consistent semantics, testable in isolation.
 *
 * <p>Accounts with status {@code ACTIVE} pass through. Accounts with
 * {@code PENDING_CONSENT} throw {@link ConsentRequiredException} which the
 * global handler maps to HTTP 403 / code CONSENT_REQUIRED so the Flutter
 * client shows the consent-gate sheet instead of a raw error.
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

    private final UserJpaRepository userRepo;
    private final ConsentRecordJpaRepository consentRecordRepo;

    /// Feature flag: whether the AI data-transfer consent gate is active.
    /// Default OFF — must not break any existing behaviour.
    @Value("${app.ai-consent.enabled:false}")
    private boolean aiConsentEnabled;

    public ConsentGuard(UserJpaRepository userRepo, ConsentRecordJpaRepository consentRecordRepo) {
        this.userRepo = userRepo;
        this.consentRecordRepo = consentRecordRepo;
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
     * Checks that the user has granted AI data-transfer consent before sending their
     * data to third-party AI processors (Anthropic Claude, Google Gemini).
     *
     * <p>This gate is controlled by the {@code app.ai-consent.enabled} flag which
     * defaults to {@code false}. When disabled, this method is a no-op and all
     * existing upload and chat behaviour is unchanged.
     *
     * <p>When enabled, throws {@link ConsentRequiredException} with reason
     * {@link #REASON_AI_DATA_TRANSFER} if the user has not yet granted consent.
     *
     * <p>Wire this check at the TOP of:
     * <ul>
     *   <li>The upload pipeline entry point ({@code UploadFileUseCase.execute})</li>
     *   <li>{@code SendMessageUseCase.executeStream} — after the slot-guard check</li>
     * </ul>
     *
     * <p>TODO (LEGAL/PRODUCT): before enabling {@code app.ai-consent.enabled=true},
     * supply plain-language disclosure copy naming Anthropic (Claude) and Google (Gemini)
     * as overseas AI processors and the purpose (answering homework questions).
     * This must appear on screen BEFORE the user uploads their first note or sends
     * their first chat message.
     *
     * @param userId the authenticated user
     */
    public void requireAiConsent(String userId) {
        if (!aiConsentEnabled) {
            return; // feature flag OFF — no-op, no existing behaviour changed
        }

        boolean hasConsented = consentRecordRepo.findAll().stream()
                .anyMatch(r -> userId.equals(r.getUserId())
                        && r.getPurposes() != null
                        && r.getPurposes().contains(REASON_AI_DATA_TRANSFER));

        if (!hasConsented) {
            log.info("[Consent] AI_DATA_TRANSFER consent required for user={}", userId);
            throw new ConsentRequiredException(REASON_AI_DATA_TRANSFER);
        }
    }
}
