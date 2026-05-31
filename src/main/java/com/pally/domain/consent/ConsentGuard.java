package com.pally.domain.consent;

import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.ConsentRequiredException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
@Slf4j
public class ConsentGuard {

    public static final String STATUS_ACTIVE  = "ACTIVE";
    public static final String STATUS_PENDING = "PENDING_CONSENT";

    private final UserJpaRepository userRepo;

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
}
