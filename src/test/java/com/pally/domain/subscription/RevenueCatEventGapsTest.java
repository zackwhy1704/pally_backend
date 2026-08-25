package com.pally.domain.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the three RevenueCat event gaps, and the one event that must stay ignored.
 *
 * <p>All four previously fell into the same {@code else} branch and logged
 * "no-op". Silently ignoring an event you should handle is the failure mode for a
 * webhook: nothing errors, nothing retries, and the entitlement is quietly wrong.
 */
@ExtendWith(MockitoExtension.class)
class RevenueCatEventGapsTest {

    private static final String SECRET = "rc-secret-123";
    private static final String USER = "user-1";

    @Mock SubscriptionRepository subscriptionRepo;
    @Mock PremiumService premiumService;
    @Mock ProcessedRevenueCatEventRepository processedEventRepo;

    private RevenueCatWebhookService service() {
        when(processedEventRepo.claimEvent(anyString(), anyString(), any())).thenReturn(true);
        return new RevenueCatWebhookService(
                subscriptionRepo, premiumService, processedEventRepo, new ObjectMapper(), SECRET);
    }

    private SubscriptionRepository.Subscription active(String userId, String plan) {
        return new SubscriptionRepository.Subscription(
                userId, null, null, plan, "active",
                Instant.now().plus(30, ChronoUnit.DAYS), false, null,
                Instant.now().minus(30, ChronoUnit.DAYS), Instant.now(), Instant.now());
    }

    private String event(String type, String extra) {
        return "{\"event\":{\"id\":\"evt-" + System.nanoTime() + "\",\"type\":\"" + type
                + "\",\"app_user_id\":\"" + USER + "\"" + extra + "}}";
    }

    private SubscriptionRepository.Subscription captureSaved() {
        ArgumentCaptor<SubscriptionRepository.Subscription> c =
                ArgumentCaptor.forClass(SubscriptionRepository.Subscription.class);
        verify(subscriptionRepo, atLeastOnce()).save(c.capture());
        return c.getValue();
    }

    // ── GAP 1: REFUND (the real hole) ────────────────────────────────────────

    @Test
    void refund_revokesAccess() {
        // THE WORST OF THE THREE. A refunded purchase was unhandled, so the money
        // went back and the access stayed — forever, since no later event would
        // arrive to correct it.
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.of(active(USER, "pro")));

        service().handle(event("REFUND", ""));

        assertThat(captureSaved().status())
                .as("a refunded purchase must lose access")
                .isEqualTo("free");
        verify(premiumService).evictEntitlement(USER);
    }

    // ── GAP 2: TRANSFER / SUBSCRIBER_ALIAS ───────────────────────────────────

    @Test
    void transfer_movesEntitlement_fromOriginToDestination() {
        // We key entitlement on OUR userId, so a transfer landing on the wrong
        // account silently revokes a payer and grants a stranger.
        when(subscriptionRepo.findById("old-user")).thenReturn(Optional.of(active("old-user", "pro")));
        when(subscriptionRepo.findById("new-user")).thenReturn(Optional.empty());

        service().handle("{\"event\":{\"id\":\"evt-t1\",\"type\":\"TRANSFER\","
                + "\"app_user_id\":\"" + USER + "\","
                + "\"transferred_from\":[\"old-user\"],\"transferred_to\":[\"new-user\"]}}");

        ArgumentCaptor<SubscriptionRepository.Subscription> c =
                ArgumentCaptor.forClass(SubscriptionRepository.Subscription.class);
        verify(subscriptionRepo, atLeastOnce()).save(c.capture());
        assertThat(c.getAllValues())
                .as("origin downgraded and destination granted")
                .anyMatch(s -> s.userId().equals("old-user") && "free".equals(s.status()))
                .anyMatch(s -> s.userId().equals("new-user") && "active".equals(s.status()));
    }

    @Test
    void transfer_withMissingIds_changesNothing() {
        // A HALF-applied transfer is worse than an unapplied one: it revokes a
        // paying user and grants nobody.
        service().handle(event("TRANSFER", ""));

        verify(subscriptionRepo, never()).save(any());
    }

    // ── GAP 3: BILLING_ISSUE — grace, not revocation ─────────────────────────

    @Test
    void billingIssue_doesNotRevoke_itIsAGracePeriod() {
        // Revoking here would cut off a paying user over a card that is about to
        // retry. RevenueCat sends EXPIRATION if the grace period actually lapses.
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.of(active(USER, "pro")));

        service().handle(event("BILLING_ISSUE", ""));

        assertThat(captureSaved().status())
                .as("billing issue is a grace period, not a revocation")
                .isEqualTo("active");
    }

    @Test
    void billingIssue_stampsVerification() {
        // We still LEARNED something about this subscriber, so the staleness clock
        // resets — otherwise a grace period would age the row into a re-check.
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.of(active(USER, "pro")));

        service().handle(event("BILLING_ISSUE", ""));

        assertThat(captureSaved().lastVerifiedAt()).isNotNull();
    }

    // ── CANCELLATION stays ignored (deliberately) ────────────────────────────

    @Test
    void cancellation_doesNotRevoke_accessRunsToExpiration() {
        // In RevenueCat, CANCELLATION means auto-renew OFF, not access lost.
        // Revoking here would cut off someone who has paid through period end.
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.of(active(USER, "pro")));

        service().handle(event("CANCELLATION", ""));

        assertThat(captureSaved().status())
                .as("cancellation must not end access early")
                .isEqualTo("active");
    }

    // ── grants stamp the staleness clock ─────────────────────────────────────

    @Test
    void grant_stampsLastVerifiedAt_soStalenessIsComputable() {
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.empty());

        service().handle(event("INITIAL_PURCHASE", ",\"product_id\":\"apalchi_pro_monthly\""));

        SubscriptionRepository.Subscription saved = captureSaved();
        assertThat(saved.status()).isEqualTo("active");
        assertThat(saved.lastVerifiedAt())
                .as("without this the 24h staleness bound cannot be computed")
                .isNotNull();
    }
}
