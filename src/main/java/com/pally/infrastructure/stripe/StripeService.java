package com.pally.infrastructure.stripe;

import com.pally.shared.exception.BusinessException;
import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.billingportal.Session;
import com.stripe.net.Webhook;
import com.stripe.param.billingportal.SessionCreateParams;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData;
import com.stripe.param.checkout.SessionCreateParams.LineItem.PriceData.Recurring;
import com.stripe.param.checkout.SessionCreateParams.Mode;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Thin adapter over the Stripe Java SDK. Two non-obvious choices:
 *
 *  1. We don't fail-fast when keys are missing — SubscriptionController
 *     keeps a mock branch the pilot still depends on, so the service
 *     just reports {@link #isLive()} = false and throws if a live-only
 *     method is called.
 *  2. We construct Checkout Sessions with {@code priceData} only when no
 *     price ID is configured. Once Stripe Dashboard products are set up
 *     and STRIPE_PRICE_* are populated, we hand Stripe the real price IDs
 *     so amount/currency live in one place.
 */
@Service
@Slf4j
public class StripeService {

    @Value("${stripe.secret-key:}")
    private String secretKey;

    @Value("${stripe.webhook-secret:}")
    private String webhookSecret;

    @Value("${stripe.return.success-url:pally://subscription/return?status=success}")
    private String successUrl;

    @Value("${stripe.return.cancel-url:pally://subscription/return?status=cancel}")
    private String cancelUrl;

    @Value("${stripe.price.individual-monthly:}")
    private String priceIndividualMonthly;

    @Value("${stripe.price.family-monthly:}")
    private String priceFamilyMonthly;

    @PostConstruct
    void init() {
        if (isLive()) {
            Stripe.apiKey = secretKey;
            log.info("[Stripe] Live mode configured");
        } else {
            log.info("[Stripe] STRIPE_SECRET_KEY not set — mock mode");
        }
    }

    public boolean isLive() {
        return secretKey != null && !secretKey.isBlank();
    }

    /// Creates a hosted Checkout Session and returns the URL the client
    /// should open. {@code userId} flows through as client_reference_id
    /// so the webhook can resolve the subscription back to the user.
    public String createCheckoutSession(String userId, String plan) {
        if (!isLive()) {
            throw new BusinessException("Stripe is not configured", 503);
        }
        try {
            var params = com.stripe.param.checkout.SessionCreateParams.builder()
                    .setMode(Mode.SUBSCRIPTION)
                    .setClientReferenceId(userId)
                    .setSuccessUrl(successUrl)
                    .setCancelUrl(cancelUrl)
                    .setSubscriptionData(com.stripe.param.checkout
                            .SessionCreateParams.SubscriptionData.builder()
                            .setTrialPeriodDays(7L)
                            .putMetadata("userId", userId)
                            .putMetadata("plan", plan)
                            .build())
                    .addLineItem(buildLineItem(plan))
                    .build();
            var session = com.stripe.model.checkout.Session.create(params);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("[Stripe] checkout failed user={} plan={}: {}",
                    userId, plan, e.getMessage());
            throw new BusinessException(
                    "Could not start checkout — try again", 502);
        }
    }

    /// Build a line item — prefer real price IDs once configured; fall back
    /// to ad-hoc priceData for the pre-Dashboard pilot so the flow can be
    /// exercised end-to-end without leaving placeholder products lying around.
    private com.stripe.param.checkout.SessionCreateParams.LineItem buildLineItem(
            String plan) {
        String priceId = resolvePriceId(plan);
        if (priceId != null && !priceId.isBlank()) {
            return com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                    .setPrice(priceId)
                    .setQuantity(1L)
                    .build();
        }
        long amount = "family_monthly".equals(plan) ? 1499L : 799L;
        String name = "family_monthly".equals(plan)
                ? "Pally Family Monthly"
                : "Pally Premium Monthly";
        return com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(PriceData.builder()
                        .setCurrency("usd")
                        .setUnitAmount(amount)
                        .setRecurring(Recurring.builder()
                                .setInterval(Recurring.Interval.MONTH)
                                .build())
                        .setProductData(PriceData.ProductData.builder()
                                .setName(name)
                                .build())
                        .build())
                .build();
    }

    private String resolvePriceId(String plan) {
        return "family_monthly".equals(plan)
                ? priceFamilyMonthly
                : priceIndividualMonthly;
    }

    /// Opens a Billing Portal session so the user can manage / cancel / swap
    /// payment method without bouncing through engineering.
    public String createPortalSession(String customerId) {
        if (!isLive()) {
            throw new BusinessException("Stripe is not configured", 503);
        }
        if (customerId == null || customerId.isBlank()) {
            throw new BusinessException(
                    "No Stripe customer on file — subscribe first", 409);
        }
        try {
            var params = SessionCreateParams.builder()
                    .setCustomer(customerId)
                    .setReturnUrl(successUrl)
                    .build();
            Session session = Session.create(params);
            return session.getUrl();
        } catch (StripeException e) {
            log.error("[Stripe] portal failed customer={}: {}",
                    customerId, e.getMessage());
            throw new BusinessException(
                    "Could not open billing portal — try again", 502);
        }
    }

    /// Throws {@link BusinessException} 400 on a bad signature — webhook
    /// caller catches and returns 400 so Stripe retries.
    public Event verifyWebhook(String payload, String sigHeader) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new BusinessException(
                    "Webhook secret not configured", 503);
        }
        try {
            return Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("[Stripe] bad webhook signature: {}", e.getMessage());
            throw new BusinessException("Invalid signature", 400);
        }
    }

}
