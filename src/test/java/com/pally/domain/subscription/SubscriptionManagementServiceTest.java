package com.pally.domain.subscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
