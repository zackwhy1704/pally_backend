package com.pally.domain.subscription;

import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.shared.exception.UpgradeRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Caps ACCEPTED uploads per user per rolling 30-day window, by tier. The compile
 * (~25c on Gemini) is the expensive op, so unlimited FREE uploads bleed cost
 * against zero revenue — this gate stops that leak. Paid tiers get a generous or
 * unlimited cap (see {@link Entitlements}).
 *
 * <p>"Accepted" = files that reached {@code READY} (triggered a compile); a
 * dedup/relevance-rejected upload never becomes READY, so it never burns quota
 * (see {@link KnowledgeRepository#countAcceptedUploadsSince}). Enforced at the
 * TOP of the upload use case so a capped user fails fast (no OCR/storage spent).
 *
 * <p>Window is a ROLLING 30 days (not a calendar month) — fairer + no reset-day
 * gaming. FREE's cap is config-tunable ({@code subscription.free.upload-cap})
 * without a deploy; paid caps come from the canonical {@link Entitlements}.
 */
@Service
@Slf4j
public class UploadQuotaGuard {

    private static final Duration WINDOW = Duration.ofDays(30);

    private final PremiumService premiumService;
    private final KnowledgeRepository knowledgeRepository;
    private final int freeUploadCap;

    public UploadQuotaGuard(
            PremiumService premiumService,
            KnowledgeRepository knowledgeRepository,
            @Value("${subscription.free.upload-cap:5}") int freeUploadCap) {
        this.premiumService = premiumService;
        this.knowledgeRepository = knowledgeRepository;
        this.freeUploadCap = freeUploadCap;
    }

    /**
     * Throws {@link UpgradeRequiredException}("UPLOAD_DOC") (HTTP 402) when a user
     * has hit their tier's upload cap. No-op for tiers within cap / unlimited.
     */
    public void requireUploadQuota(String userId) {
        SubscriptionTier tier = premiumService.resolveTier(userId);
        int cap = tier == SubscriptionTier.FREE
                ? freeUploadCap                                    // config-tunable
                : Entitlements.forTier(tier).monthlyUploadCap();   // canonical
        if (Entitlements.isUnlimited(cap)) return;

        int used = knowledgeRepository.countAcceptedUploadsSince(
                userId, Instant.now().minus(WINDOW));
        if (used >= cap) {
            log.info("[UploadCap] user={} tier={} used={}/{} in 30d — upgrade required",
                    userId, tier, used, cap);
            throw new UpgradeRequiredException("UPLOAD_DOC");
        }
    }
}
