package com.pally.domain.subscription;

import com.pally.domain.knowledge.KnowledgeRepository;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FREE-tier upload cap (the known margin leak): unlimited free uploads bleed
 * compile cost against zero revenue. This gate caps accepted uploads by tier.
 */
@ExtendWith(MockitoExtension.class)
class UploadQuotaGuardTest {

    @Mock PremiumService premiumService;
    @Mock KnowledgeRepository knowledgeRepository;

    private static final int FREE_CAP = 5;

    private UploadQuotaGuard guard() {
        return new UploadQuotaGuard(premiumService, knowledgeRepository, FREE_CAP);
    }

    @Test
    void free_atCap_rejectsWithUpgradeRequired_uploadDoc() {
        when(premiumService.resolveTier("u")).thenReturn(SubscriptionTier.FREE);
        when(knowledgeRepository.countAcceptedUploadsSince(eq("u"), any(Instant.class))).thenReturn(FREE_CAP);

        assertThatThrownBy(() -> guard().requireUploadQuota("u"))
                .isInstanceOf(UpgradeRequiredException.class)
                .satisfies(e -> assertThat(((UpgradeRequiredException) e).getFeature()).isEqualTo("UPLOAD_DOC"));
    }

    @Test
    void free_underCap_proceeds() {
        when(premiumService.resolveTier("u")).thenReturn(SubscriptionTier.FREE);
        when(knowledgeRepository.countAcceptedUploadsSince(eq("u"), any(Instant.class))).thenReturn(FREE_CAP - 1);

        assertThatCode(() -> guard().requireUploadQuota("u")).doesNotThrowAnyException();
    }

    @Test
    void pro_getsAGenerousCap_50() {
        when(premiumService.resolveTier("u")).thenReturn(SubscriptionTier.PRO);
        // The entitlement (not the FREE config) drives PRO's cap.
        when(knowledgeRepository.countAcceptedUploadsSince(eq("u"), any(Instant.class))).thenReturn(49);
        assertThatCode(() -> guard().requireUploadQuota("u")).doesNotThrowAnyException();

        when(knowledgeRepository.countAcceptedUploadsSince(eq("u"), any(Instant.class))).thenReturn(50);
        assertThatThrownBy(() -> guard().requireUploadQuota("u"))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    void maxAndFamily_areUnlimited_neverEvenCount() {
        for (SubscriptionTier tier : new SubscriptionTier[]{SubscriptionTier.MAX, SubscriptionTier.FAMILY}) {
            when(premiumService.resolveTier("u")).thenReturn(tier);
            assertThatCode(() -> guard().requireUploadQuota("u")).doesNotThrowAnyException();
        }
        // Unlimited short-circuits before the count query — no wasted DB hit.
        verify(knowledgeRepository, never()).countAcceptedUploadsSince(any(), any());
    }

    @Test
    void freeCapIsConfigDriven_notHardcoded() {
        // A different configured cap (3) is honoured — tunable from data without a deploy.
        UploadQuotaGuard tight = new UploadQuotaGuard(premiumService, knowledgeRepository, 3);
        when(premiumService.resolveTier("u")).thenReturn(SubscriptionTier.FREE);
        when(knowledgeRepository.countAcceptedUploadsSince(eq("u"), any(Instant.class))).thenReturn(3);

        assertThatThrownBy(() -> tight.requireUploadQuota("u"))
                .isInstanceOf(UpgradeRequiredException.class);
    }
}
