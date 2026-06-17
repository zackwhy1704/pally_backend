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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
