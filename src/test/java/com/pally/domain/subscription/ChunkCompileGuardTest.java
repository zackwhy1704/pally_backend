package com.pally.domain.subscription;

import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The chunk-compile allowance gate — the exact mirror of the upload cap. FREE hits
 * a small cap; paid/centre-resolved tiers hit the SAME code path (only the number
 * differs); the count is success-based so a failed compile never burns allowance.
 */
@ExtendWith(MockitoExtension.class)
class ChunkCompileGuardTest {

    @Mock PremiumService premiumService;
    @Mock KnowledgeRepository knowledgeRepository;

    private static final int FREE_CAP = 5;

    private ChunkCompileGuard guard() {
        return new ChunkCompileGuard(premiumService, knowledgeRepository, FREE_CAP);
    }

    @Test
    void free_atCap_rejects_withChunkCompileFeature() {
        when(premiumService.resolveTier("u")).thenReturn(SubscriptionTier.FREE);
        when(knowledgeRepository.countChunkCompilesSince(eq("u"), any(Instant.class))).thenReturn(FREE_CAP);

        assertThatThrownBy(() -> guard().requireChunkCompileQuota("u"))
                .isInstanceOf(UpgradeRequiredException.class)
                .satisfies(e -> assertThat(((UpgradeRequiredException) e).getFeature()).isEqualTo("CHUNK_COMPILE"));
    }

    @Test
    void free_underCap_proceeds() {
        when(premiumService.resolveTier("u")).thenReturn(SubscriptionTier.FREE);
        when(knowledgeRepository.countChunkCompilesSince(eq("u"), any(Instant.class))).thenReturn(FREE_CAP - 1);
        assertThatCode(() -> guard().requireChunkCompileQuota("u")).doesNotThrowAnyException();
    }

    @Test
    void pro_finiteButGenerous_100_sameCodePath() {
        when(premiumService.resolveTier("u")).thenReturn(SubscriptionTier.PRO);
        when(knowledgeRepository.countChunkCompilesSince(eq("u"), any(Instant.class))).thenReturn(99);
        assertThatCode(() -> guard().requireChunkCompileQuota("u")).doesNotThrowAnyException();

        when(knowledgeRepository.countChunkCompilesSince(eq("u"), any(Instant.class))).thenReturn(100);
        assertThatThrownBy(() -> guard().requireChunkCompileQuota("u"))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    void maxAndFamily_unlimited_neverEvenCount_theTierCentreStudentsResolveTo() {
        for (SubscriptionTier tier : new SubscriptionTier[]{SubscriptionTier.MAX, SubscriptionTier.FAMILY}) {
            when(premiumService.resolveTier("u")).thenReturn(tier);
            assertThatCode(() -> guard().requireChunkCompileQuota("u")).doesNotThrowAnyException();
        }
        verify(knowledgeRepository, never()).countChunkCompilesSince(any(), any());
    }

    @Test
    void allowance_reportsUsedAndLimit_andUnlimitedAsMinusOne() {
        when(premiumService.resolveTier("free")).thenReturn(SubscriptionTier.FREE);
        when(knowledgeRepository.countChunkCompilesSince(eq("free"), any(Instant.class))).thenReturn(2);
        ChunkCompileGuard.ChunkAllowance a = guard().allowance("free");
        assertThat(a.used()).isEqualTo(2);
        assertThat(a.limit()).isEqualTo(FREE_CAP);
        assertThat(a.unlimited()).isFalse();

        when(premiumService.resolveTier("max")).thenReturn(SubscriptionTier.MAX);
        ChunkCompileGuard.ChunkAllowance u = guard().allowance("max");
        assertThat(u.limit()).isEqualTo(-1);
        assertThat(u.unlimited()).isTrue();
    }
}
