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

    // Default to the HTTPS redirect endpoint which bounces → pally:// deep link.
    // Stripe Checkout rejects custom-scheme deep links as success_url.
    @Value("${stripe.return.success-url:https://pallybackend-production.up.railway.app/api/v1/subscription/return?status=success}")
    private String successUrl;

    @Value("${stripe.return.cancel-url:https://pallybackend-production.up.railway.app/api/v1/subscription/return?status=cancel}")
    private String cancelUrl;

    // ── Legacy price IDs ────────────────────────────────────────────────────
    @Value("${stripe.price.individual-monthly:}")
    private String priceIndividualMonthly;

    @Value("${stripe.price.family-monthly:}")
    private String priceFamilyMonthlyLegacy;

    // ── Current price IDs (all 8 canonical plan keys) ───────────────────────
    @Value("${stripe.price.pro-monthly:}")
    private String priceProMonthly;

    @Value("${stripe.price.pro-annual:}")
    private String priceProAnnual;

    @Value("${stripe.price.max-monthly:}")
    private String priceMaxMonthly;

    @Value("${stripe.price.max-annual:}")
    private String priceMaxAnnual;

    @Value("${stripe.price.family-monthly-new:}")
    private String priceFamilyMonthlyNew;

    @Value("${stripe.price.family-annual:}")
    private String priceFamilyAnnual;

    @Value("${stripe.price.centre-monthly:}")
    private String priceCentreMonthly;

    @Value("${stripe.price.centre-annual:}")
    private String priceCentreAnnual;

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

    // Canonical allowed plan keys. All others are rejected at checkout time.
    private static final java.util.Set<String> ALLOWED_PLANS = java.util.Set.of(
            "pro_monthly", "pro_annual",
            "max_monthly", "max_annual",
            "family_monthly", "family_monthly_new", "family_annual",
            "centre_monthly", "centre_annual",
            // Legacy — kept for backward compat
            "individual_monthly"
    );

    /// Creates a hosted Checkout Session and returns the URL the client
    /// should open. {@code userId} flows through as client_reference_id
    /// so the webhook can resolve the subscription back to the user.
    ///
    /// <p>Validates that {@code plan} is a recognised canonical key before
    /// building Stripe params, so unknown plan strings don't create orphaned
    /// checkout sessions.
    public String createCheckoutSession(String userId, String plan) {
        // Validate plan BEFORE isLive check so clients get a clear 400 for
        // bad plan keys regardless of whether Stripe is configured.
        if (plan == null || !ALLOWED_PLANS.contains(plan)) {
            throw new BusinessException("Unknown plan: " + plan, 400);
        }
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
            log.error("[Stripe] checkout failed user={} plan={} code={} type={}: {}",
                    userId, plan, e.getCode(), e.getClass().getSimpleName(), e.getMessage());
            throw new BusinessException(
                    "Could not start checkout — try again", 502);
        }
    }

    /// Build a line item — prefer real price IDs once configured; fall back
    /// to ad-hoc priceData with correct amounts for each plan.
    private com.stripe.param.checkout.SessionCreateParams.LineItem buildLineItem(
            String plan) {
        String priceId = resolvePriceId(plan);
        if (priceId != null && !priceId.isBlank()) {
            return com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                    .setPrice(priceId)
                    .setQuantity(1L)
                    .build();
        }
        // Fallback priceData with correct amounts per plan (cents, USD).
        long amount = fallbackAmount(plan);
        Recurring.Interval interval = plan.endsWith("_annual")
                ? Recurring.Interval.YEAR
                : Recurring.Interval.MONTH;
        String productName = "Pally " + planDisplayName(plan);
        return com.stripe.param.checkout.SessionCreateParams.LineItem.builder()
                .setQuantity(1L)
                .setPriceData(PriceData.builder()
                        .setCurrency("usd")
                        .setUnitAmount(amount)
                        .setRecurring(Recurring.builder()
                                .setInterval(interval)
                                .build())
                        .setProductData(PriceData.ProductData.builder()
                                .setName(productName)
                                .build())
                        .build())
                .build();
    }

    /**
     * Resolves a canonical (or legacy) plan key to a Stripe Price ID.
     * Returns null/blank if not configured — caller falls back to priceData.
     */
    String resolvePriceId(String plan) {
        return switch (plan) {
            case "pro_monthly"                    -> priceProMonthly;
            case "pro_annual"                     -> priceProAnnual;
            case "max_monthly"                    -> priceMaxMonthly;
            case "max_annual"                     -> priceMaxAnnual;
            case "family_monthly", "family_monthly_new" -> priceFamilyMonthlyNew;
            case "family_annual"                  -> priceFamilyAnnual;
            case "centre_monthly"                 -> priceCentreMonthly;
            case "centre_annual"                  -> priceCentreAnnual;
            // Legacy plan → route to PRO price
            case "individual_monthly"             ->
                    priceProMonthly != null && !priceProMonthly.isBlank()
                            ? priceProMonthly
                            : priceIndividualMonthly;
            default -> null;
        };
    }

    private long fallbackAmount(String plan) {
        return switch (plan) {
            case "pro_monthly"                         ->  999L;
            case "pro_annual"                          -> 7900L;
            case "max_monthly"                         -> 1999L;
            case "max_annual"                          -> 15900L;
            case "family_monthly", "family_monthly_new"-> 3499L;
            case "family_annual"                       -> 27900L;
            case "centre_monthly"                      -> 8999L;
            case "centre_annual"                       -> 72000L;
            // Legacy
            case "individual_monthly"                  ->  999L;
            default                                    ->  999L;
        };
    }

    private String planDisplayName(String plan) {
        return switch (plan) {
            case "pro_monthly", "pro_annual"             -> "Pro";
            case "max_monthly", "max_annual"             -> "Max";
            case "family_monthly", "family_monthly_new",
                 "family_annual"                         -> "Family";
            case "centre_monthly", "centre_annual"       -> "Centre";
            default                                      -> "Premium";
        };
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

    /// Cancels the Stripe subscription immediately for the given user, looked up
    /// via the subscriptions table. Intended for account deletion flows.
    ///
    /// <p>Fails silently if Stripe is not configured or the subscription ID is
    /// absent — account deletion must proceed regardless of Stripe state.
    public void cancelSubscriptionForUser(String stripeSubscriptionId) {
        if (!isLive() || stripeSubscriptionId == null || stripeSubscriptionId.isBlank()) {
            log.info("[Stripe] cancelSubscriptionForUser skipped — not live or no sub id");
            return;
        }
        try {
            var subscription = com.stripe.model.Subscription.retrieve(stripeSubscriptionId);
            subscription.cancel();
            log.info("[Stripe] Subscription cancelled id={}", stripeSubscriptionId);
        } catch (StripeException e) {
            // Log and swallow — account deletion must not be blocked by Stripe failures.
            log.warn("[Stripe] cancelSubscription failed id={}: {} ({})",
                    stripeSubscriptionId, e.getMessage(), e.getCode());
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
