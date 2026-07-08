package com.pally.domain.subscription;

import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.shared.exception.UpgradeRequiredException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Caps chapter-CHUNK compiles per user per rolling 30-day window, by tier — the
 * exact mirror of {@link UploadQuotaGuard}. Each chunk compile is the same
 * expensive op an upload triggers, so FREE gets a small allowance and paid tiers a
 * generous/unlimited one (see {@link Entitlements#monthlyChunkCompiles}).
 *
 * <p>SUCCESS-BASED (never charge for what never compiled): the count is children
 * whose compile COMPLETED (compiled_at stamped) in the window
 * ({@link KnowledgeRepository#countChunkCompilesSince}). A picked-but-failed chunk
 * (compiled_at null) never burns allowance; a retry that finally succeeds counts once.
 *
 * <p>CENTRE/B2B parity: centre-source students inherit a PAID tier server-side, so
 * they land on the unlimited arm through the SAME code path — there is no B2B branch
 * here. The only tier difference is the allowance NUMBER in {@link Entitlements}.
 */
@Service
@Slf4j
public class ChunkCompileGuard {

    private static final Duration WINDOW = Duration.ofDays(30);

    private final PremiumService premiumService;
    private final KnowledgeRepository knowledgeRepository;
    private final int freeChunkCap;

    public ChunkCompileGuard(
            PremiumService premiumService,
            KnowledgeRepository knowledgeRepository,
            @Value("${subscription.free.chunk-compile-cap:5}") int freeChunkCap) {
        this.premiumService = premiumService;
        this.knowledgeRepository = knowledgeRepository;
        this.freeChunkCap = freeChunkCap;
    }

    /** The allowance a user has left this window — drives the picker's "N of M" counter. */
    public record ChunkAllowance(int used, int limit) {
        public boolean unlimited() { return Entitlements.isUnlimited(limit); }
    }

    private int capFor(SubscriptionTier tier) {
        return tier == SubscriptionTier.FREE
                ? freeChunkCap                                        // config-tunable
                : Entitlements.forTier(tier).monthlyChunkCompiles();  // canonical
    }

    /**
     * Throws {@link UpgradeRequiredException}("CHUNK_COMPILE") (HTTP 402) when the user
     * has hit their tier's chunk-compile cap. No-op for unlimited tiers / within cap.
     * Call ONLY when transitioning a chunk from PENDING_CHUNK → picked (first compile),
     * so a re-pick of an already-READY chunk never re-checks.
     */
    public void requireChunkCompileQuota(String userId) {
        SubscriptionTier tier = premiumService.resolveTier(userId);
        int cap = capFor(tier);
        if (Entitlements.isUnlimited(cap)) return;

        int used = knowledgeRepository.countChunkCompilesSince(userId, Instant.now().minus(WINDOW));
        if (used >= cap) {
            log.info("[ChunkCap] user={} tier={} used={}/{} in 30d — upgrade required",
                    userId, tier, used, cap);
            throw new UpgradeRequiredException("CHUNK_COMPILE");
        }
    }

    /** Read the user's current chunk-compile allowance (for the picker counter). */
    public ChunkAllowance allowance(String userId) {
        SubscriptionTier tier = premiumService.resolveTier(userId);
        int cap = capFor(tier);
        if (Entitlements.isUnlimited(cap)) return new ChunkAllowance(0, -1);
        int used = knowledgeRepository.countChunkCompilesSince(userId, Instant.now().minus(WINDOW));
        return new ChunkAllowance(used, cap);
    }
}
