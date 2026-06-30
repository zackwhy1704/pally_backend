package com.pally.domain.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import com.stripe.model.Event;
import com.stripe.net.ApiResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for plan-validation in {@link SubscriptionManagementService#createCheckout}.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionManagementServiceTest {

    @Mock SubscriptionRepository subscriptionRepo;
    @Mock ProcessedStripeEventRepository processedEventRepo;
    @Mock PremiumService premiumService;
    @Mock StripeService stripeService;
    @Mock ObjectMapper objectMapper;

    @InjectMocks SubscriptionManagementService service;

    @Test
    void createCheckout_centreMonthlyPlan_isRejectedWith400() {
        // CENTRE consumer tier no longer exists — guard must fire regardless of live/mock mode.
        ReflectionTestUtils.setField(service, "stripeSecretKey", "");
        assertThatThrownBy(() -> service.createCheckout("user-1", "centre_monthly"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void createCheckout_centreAnnualPlan_isRejectedWith400() {
        ReflectionTestUtils.setField(service, "stripeSecretKey", "");
        assertThatThrownBy(() -> service.createCheckout("user-1", "CENTRE_annual"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void createCheckout_blankPlan_isRejectedWith400() {
        ReflectionTestUtils.setField(service, "stripeSecretKey", "");
        assertThatThrownBy(() -> service.createCheckout("user-1", ""))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    @Test
    void createCheckout_mockMode_returnsLiveFalseSoWebCanShowPaymentsUnavailable() {
        // No Stripe key → mock path. The web must be able to tell this is a
        // placeholder URL, not a real checkout, via an explicit boolean.
        ReflectionTestUtils.setField(service, "stripeSecretKey", "");

        Map<String, Object> result = service.createCheckout("user-1", "pro_monthly");

        assertThat(result).containsEntry("live", false);
        assertThat(result).containsEntry("mode", "mock");
        assertThat((String) result.get("checkoutUrl")).contains("mock-checkout");
    }

    /**
     * THE "paid but stays Free" REGRESSION GUARD.
     *
     * <p>When the Stripe webhook endpoint's api_version differs from the version
     * this SDK is pinned to, {@code getDataObjectDeserializer().getObject()}
     * returns EMPTY. The old code threw on that and the caller swallowed it with
     * a 200 — so a real {@code checkout.session.completed} silently failed to
     * upgrade the user. The unsafe-deserialize fallback must still parse the
     * session and activate the subscription despite the version skew.
     */
    @Test
    void checkoutCompleted_withApiVersionSkew_stillActivatesSubscription() {
        // api_version deliberately NOT the SDK's pinned version → getObject() empty.
        String json = """
            {
              "id": "evt_skew_1",
              "object": "event",
              "api_version": "2030-01-01.future",
              "type": "checkout.session.completed",
              "data": { "object": {
                "id": "cs_live_123",
                "object": "checkout.session",
                "client_reference_id": "user-skew",
                "customer": "cus_123",
                "subscription": "sub_123",
                "metadata": { "plan": "pro_monthly" }
              } }
            }
            """;
        Event event = ApiResource.GSON.fromJson(json, Event.class);
        // Sanity: this is exactly the production failure shape — the typed object
        // is NOT directly deserializable because of the version skew.
        assertThat(event.getDataObjectDeserializer().getObject()).isEmpty();

        when(subscriptionRepo.findById("user-skew")).thenReturn(Optional.empty());

        ReflectionTestUtils.invokeMethod(service, "handleStripeEvent", event);

        ArgumentCaptor<SubscriptionRepository.Subscription> saved =
                ArgumentCaptor.forClass(SubscriptionRepository.Subscription.class);
        verify(subscriptionRepo).save(saved.capture());
        assertThat(saved.getValue().userId()).isEqualTo("user-skew");
        assertThat(saved.getValue().status()).isEqualTo("active");
        assertThat(saved.getValue().stripeCustomerId()).isEqualTo("cus_123");
        assertThat(saved.getValue().stripeSubscriptionId()).isEqualTo("sub_123");
        assertThat(saved.getValue().plan()).isEqualTo("pro_monthly");
        verify(premiumService).refreshFlag("user-skew");
        verify(premiumService).convertTrial("user-skew");
    }

    @Test
    void createCheckout_liveMode_returnsLiveTrueWithRealStripeUrl() {
        // A non-blank secret key makes isLive() true → real Stripe path. We mock
        // the Stripe adapter so no real network/keys are needed.
        ReflectionTestUtils.setField(service, "stripeSecretKey", "sk_test_dummy");
        when(stripeService.createCheckoutSession("user-1", "pro_monthly"))
                .thenReturn("https://checkout.stripe.com/c/pay/cs_test_123");

        Map<String, Object> result = service.createCheckout("user-1", "pro_monthly");

        assertThat(result).containsEntry("live", true);
        assertThat(result).containsEntry("mode", "live");
        assertThat(result).containsEntry(
                "checkoutUrl", "https://checkout.stripe.com/c/pay/cs_test_123");
    }
}
