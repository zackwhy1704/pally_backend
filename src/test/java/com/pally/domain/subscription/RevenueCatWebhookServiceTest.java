package com.pally.domain.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueCatWebhookServiceTest {

    private static final String SECRET = "rc-secret-123";
    private static final String USER = "user-1";

    @Mock SubscriptionRepository subscriptionRepo;
    @Mock PremiumService premiumService;

    private RevenueCatWebhookService service() {
        return new RevenueCatWebhookService(subscriptionRepo, premiumService, new ObjectMapper(), SECRET);
    }

    private RevenueCatWebhookService serviceWithBlankSecret() {
        return new RevenueCatWebhookService(subscriptionRepo, premiumService, new ObjectMapper(), "");
    }

    // ── auth ─────────────────────────────────────────────────────────────────

    @Test
    void isAuthorized_matchingSecret_true_mismatchOrMissing_false() {
        RevenueCatWebhookService svc = service();
        assertThat(svc.isAuthorized(SECRET)).isTrue();
        assertThat(svc.isAuthorized("wrong")).isFalse();
        assertThat(svc.isAuthorized(null)).isFalse();
    }

    @Test
    void isAuthorized_failsClosed_whenSecretUnconfigured() {
        assertThat(serviceWithBlankSecret().isAuthorized("anything")).isFalse();
        assertThat(serviceWithBlankSecret().isAuthorized("")).isFalse();
    }

    // ── grant ────────────────────────────────────────────────────────────────

    @Test
    void handle_initialPurchase_grantsActiveSubscription_andRefreshesFlag() {
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.empty());
        String body = """
            {"event":{"type":"INITIAL_PURCHASE","app_user_id":"user-1",
              "product_id":"apalchi_max_monthly","expiration_at_ms":1700000000000}}
            """;

        var result = service().handle(body);

        assertThat(result.get("handled")).isEqualTo(true);
        ArgumentCaptor<SubscriptionRepository.Subscription> captor =
                ArgumentCaptor.forClass(SubscriptionRepository.Subscription.class);
        verify(subscriptionRepo).save(captor.capture());
        assertThat(captor.getValue().userId()).isEqualTo(USER);
        assertThat(captor.getValue().plan()).isEqualTo("max");
        assertThat(captor.getValue().status()).isEqualTo("active");
        verify(premiumService).refreshFlag(USER);
    }

    // ── downgrade ────────────────────────────────────────────────────────────

    @Test
    void handle_expiration_downgradesToFree_evictsAndRefreshes() {
        var existing = new SubscriptionRepository.Subscription(
                USER, null, null, "max", "active", Instant.now(),
                false, null, Instant.now(), Instant.now());
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.of(existing));
        String body = """
            {"event":{"type":"EXPIRATION","app_user_id":"user-1","product_id":"apalchi_max_monthly"}}
            """;

        service().handle(body);

        ArgumentCaptor<SubscriptionRepository.Subscription> captor =
                ArgumentCaptor.forClass(SubscriptionRepository.Subscription.class);
        verify(subscriptionRepo).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("free");
        verify(premiumService).evictEntitlement(USER);
        verify(premiumService).refreshFlag(USER);
    }

    // ── no-op / safety ───────────────────────────────────────────────────────

    @Test
    void handle_cancellation_isNoOp_keepsAccessUntilExpiration() {
        // CANCELLATION = auto-renew off; access continues until EXPIRATION.
        String body = """
            {"event":{"type":"CANCELLATION","app_user_id":"user-1","product_id":"apalchi_pro_monthly"}}
            """;

        var result = service().handle(body);

        assertThat(result.get("handled")).isEqualTo(true);
        verify(subscriptionRepo, never()).save(any());
    }

    @Test
    void handle_missingAppUserId_returnsNotHandled_noWrites() {
        var result = service().handle("{\"event\":{\"type\":\"INITIAL_PURCHASE\"}}");

        assertThat(result.get("handled")).isEqualTo(false);
        verify(subscriptionRepo, never()).save(any());
    }

    @Test
    void handle_malformedBody_returnsNotHandled_doesNotThrow() {
        var result = service().handle("not json");
        assertThat(result.get("handled")).isEqualTo(false);
    }
}
