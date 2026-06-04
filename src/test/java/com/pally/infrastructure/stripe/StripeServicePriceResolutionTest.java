package com.pally.infrastructure.stripe;

import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies price ID resolution for all 8 canonical plan keys,
 * the legacy-compat mapping, and the unknown-plan rejection.
 *
 * <p>Uses ReflectionTestUtils to inject @Value fields without a Spring context.
 */
class StripeServicePriceResolutionTest {

    private StripeService service;

    @BeforeEach
    void setUp() {
        service = new StripeService();
        // Inject all 8 price IDs via reflection (simulates @Value binding)
        ReflectionTestUtils.setField(service, "priceProMonthly",       "price_pro_mo");
        ReflectionTestUtils.setField(service, "priceProAnnual",        "price_pro_yr");
        ReflectionTestUtils.setField(service, "priceMaxMonthly",       "price_max_mo");
        ReflectionTestUtils.setField(service, "priceMaxAnnual",        "price_max_yr");
        ReflectionTestUtils.setField(service, "priceFamilyMonthlyNew", "price_fam_mo");
        ReflectionTestUtils.setField(service, "priceFamilyAnnual",     "price_fam_yr");
        ReflectionTestUtils.setField(service, "priceCentreMonthly",    "price_ctr_mo");
        ReflectionTestUtils.setField(service, "priceCentreAnnual",     "price_ctr_yr");
        ReflectionTestUtils.setField(service, "priceIndividualMonthly","price_ind_mo");
        ReflectionTestUtils.setField(service, "priceFamilyMonthlyLegacy","price_fam_legacy");
        // Leave secretKey blank so isLive()=false (we won't call Stripe network)
        ReflectionTestUtils.setField(service, "secretKey", "");
        ReflectionTestUtils.setField(service, "webhookSecret", "");
        ReflectionTestUtils.setField(service, "successUrl", "https://example.com/success");
        ReflectionTestUtils.setField(service, "cancelUrl",  "https://example.com/cancel");
    }

    // ── All 8 canonical keys ──────────────────────────────────────────────────

    @Test
    void proMonthly_resolvesToConfiguredPriceId() {
        assertThat(service.resolvePriceId("pro_monthly")).isEqualTo("price_pro_mo");
    }

    @Test
    void proAnnual_resolvesToConfiguredPriceId() {
        assertThat(service.resolvePriceId("pro_annual")).isEqualTo("price_pro_yr");
    }

    @Test
    void maxMonthly_resolvesToConfiguredPriceId() {
        assertThat(service.resolvePriceId("max_monthly")).isEqualTo("price_max_mo");
    }

    @Test
    void maxAnnual_resolvesToConfiguredPriceId() {
        assertThat(service.resolvePriceId("max_annual")).isEqualTo("price_max_yr");
    }

    @Test
    void familyMonthly_resolvesToFamilyMonthlyNew() {
        assertThat(service.resolvePriceId("family_monthly")).isEqualTo("price_fam_mo");
    }

    @Test
    void familyMonthlyNew_resolvesToFamilyMonthlyNew() {
        assertThat(service.resolvePriceId("family_monthly_new")).isEqualTo("price_fam_mo");
    }

    @Test
    void familyAnnual_resolvesToConfiguredPriceId() {
        assertThat(service.resolvePriceId("family_annual")).isEqualTo("price_fam_yr");
    }

    @Test
    void centreMonthly_resolvesToConfiguredPriceId() {
        assertThat(service.resolvePriceId("centre_monthly")).isEqualTo("price_ctr_mo");
    }

    @Test
    void centreAnnual_resolvesToConfiguredPriceId() {
        assertThat(service.resolvePriceId("centre_annual")).isEqualTo("price_ctr_yr");
    }

    // ── Legacy compat ─────────────────────────────────────────────────────────

    @Test
    void legacyIndividualMonthly_resolvesToProMonthlyPrice() {
        assertThat(service.resolvePriceId("individual_monthly")).isEqualTo("price_pro_mo");
    }

    @Test
    void legacyIndividualMonthly_fallsBackToLegacyPriceId_whenProNotConfigured() {
        ReflectionTestUtils.setField(service, "priceProMonthly", "");
        assertThat(service.resolvePriceId("individual_monthly")).isEqualTo("price_ind_mo");
    }

    // ── Unknown plan rejection ────────────────────────────────────────────────

    @Test
    void unknownPlan_createCheckoutSession_throwsBusinessException_400() {
        assertThatThrownBy(() -> service.createCheckoutSession("user-1", "totally_fake_plan"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown plan")
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void blankPlan_throwsBusinessException_400() {
        assertThatThrownBy(() -> service.createCheckoutSession("user-1", ""))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getHttpStatus()).isEqualTo(400));
    }
}
