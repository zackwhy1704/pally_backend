package com.pally.domain.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Owns all subscription business logic: Stripe webhook parsing, checkout
 * session creation, portal session creation, and subscription reads/updates.
 *
 * <p>The controller delegates entirely to this service; it has no JPA deps.
 * {@link ObjectMapper} is fine here — it is {@code com.fasterxml.jackson},
 * not infrastructure. {@link StripeService} is accepted as a direct dep
 * because it is the only Stripe adapter and already defined under
 * {@code infrastructure/stripe}; wrapping it in another port would add noise
 * with no benefit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionManagementService {

    private final SubscriptionRepository        subscriptionRepo;
    private final ProcessedStripeEventRepository processedEventRepo;
    private final PremiumService                 premiumService;
    private final StripeService                  stripeService;
    private final ObjectMapper                   objectMapper;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.mock-webhook-secret:}")
    private String mockWebhookSecret;

    // ── Mode ──────────────────────────────────────────────────────────────────

    public boolean isLive() {
        return stripeSecretKey != null && !stripeSecretKey.isBlank();
    }

    public boolean isMockAllowed(String providedSecret) {
        return mockWebhookSecret != null && !mockWebhookSecret.isBlank()
                && mockWebhookSecret.equals(providedSecret);
    }

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Returns the current subscription status map for the given user.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getStatus(String userId) {
        SubscriptionRepository.Subscription sub = subscriptionRepo.findById(userId).orElse(null);
        SubscriptionTier tier = premiumService.resolveTier(userId);
        PremiumService.Entitlement ent = premiumService.resolve(userId);

        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("mode", isLive() ? "live" : "mock");
        body.put("tier", tier.name());
        body.put("isPremium", ent.isPremium());
        if (sub == null) {
            body.put("status", "free");
            body.put("plan", null);
            body.put("currentPeriodEnd", null);
            body.put("cancelAtPeriodEnd", false);
            body.put("canceledAt", null);
        } else {
            body.put("status", sub.status());
            body.put("plan", sub.plan());
            body.put("currentPeriodEnd",
                    sub.currentPeriodEnd() == null ? null : sub.currentPeriodEnd().toString());
            body.put("cancelAtPeriodEnd", sub.cancelAtPeriodEnd());
            body.put("canceledAt",
                    sub.canceledAt() == null ? null : sub.canceledAt().toString());
        }
        return body;
    }

    // ── Checkout ──────────────────────────────────────────────────────────────

    /**
     * Creates a checkout session URL for the given user and plan.
     * Returns a summary map: {@code mode, checkoutUrl, plan}.
     */
    @Transactional
    public Map<String, Object> createCheckout(String userId, String plan) {
        if (plan == null || plan.isBlank()) {
            throw new BusinessException("plan is required", 400);
        }
        // "centre" was a legacy consumer tier; it no longer exists as a purchasable plan.
        // B2B centre coverage is handled server-side, not via a self-serve Stripe checkout.
        if (plan.toLowerCase().contains("centre")) {
            throw new BusinessException("Plan '" + plan + "' is not available for purchase", 400);
        }
        if (!isLive()) {
            log.info("[Subscription] MOCK checkout user={} plan={}", userId, plan);
            return Map.of(
                    "mode", "mock",
                    "checkoutUrl",
                    "https://pallybackend-production.up.railway.app/mock-checkout"
                            + "?plan=" + plan + "&user=" + userId,
                    "plan", plan
            );
        }
        String url = stripeService.createCheckoutSession(userId, plan);
        log.info("[Subscription] LIVE checkout user={} plan={}", userId, plan);
        return Map.of("mode", "live", "checkoutUrl", url, "plan", plan);
    }

    // ── Portal ────────────────────────────────────────────────────────────────

    /**
     * Creates a Stripe Billing Portal URL for the given user.
     * Returns a summary map: {@code mode, url}.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> createPortal(String userId) {
        if (!isLive()) {
            return Map.of(
                    "mode", "mock",
                    "url", "https://pallybackend-production.up.railway.app/mock-portal"
            );
        }
        SubscriptionRepository.Subscription sub = subscriptionRepo.findById(userId)
                .orElseThrow(() -> new BusinessException(
                        "Subscribe first before opening portal", 409));
        String url = stripeService.createPortalSession(sub.stripeCustomerId());
        return Map.of("mode", "live", "url", url);
    }

    // ── Webhook ───────────────────────────────────────────────────────────────

    /**
     * Handles a live Stripe webhook event with idempotency.
     * Returns a response map to be forwarded to the client.
     */
    @Transactional
    public Map<String, Object> handleLiveWebhook(String rawBody, String sigHeader) {
        Event event = stripeService.verifyWebhook(rawBody, sigHeader);

        if (event.getId() == null || event.getId().isBlank()) {
            log.warn("[Subscription] webhook event missing id type={} — running",
                    event.getType());
            runHandler(event);
            return Map.of(
                    "mode", "live",
                    "type", event.getType(),
                    "id", event.getId() == null ? "" : event.getId()
            );
        }

        boolean firstDelivery;
        try {
            firstDelivery = processedEventRepo.claimEvent(
                    event.getId(), event.getType(), Instant.now());
        } catch (DataIntegrityViolationException dup) {
            firstDelivery = false;
        }

        if (!firstDelivery) {
            log.info("[Subscription] webhook duplicate event={} type={} — skipping",
                    event.getId(), event.getType());
            return Map.of(
                    "mode", "live",
                    "type", event.getType(),
                    "id", event.getId(),
                    "duplicate", true
            );
        }

        try {
            handleStripeEvent(event);
        } catch (Exception e) {
            log.error("[Subscription] webhook handler failed type={}: {} "
                            + "— releasing claim for retry", event.getType(),
                    e.getMessage(), e);
            try {
                processedEventRepo.releaseEvent(event.getId());
            } catch (Exception releaseErr) {
                log.warn("[Subscription] failed to release event claim {}: {}",
                        event.getId(), releaseErr.getMessage());
            }
        }

        return Map.of(
                "mode", "live",
                "type", event.getType(),
                "id", event.getId()
        );
    }

    /**
     * Handles a mock webhook (dev/QA path). Parses the JSON body and applies
     * the subscription directly in the database.
     */
    @Transactional
    public Map<String, Object> handleMockWebhook(String rawBody) {
        Map<String, Object> body;
        try {
            JsonNode node = objectMapper.readTree(rawBody == null ? "{}" : rawBody);
            body = objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            throw new BusinessException("Mock webhook body must be valid JSON", 400);
        }
        String userId = body == null ? null : (String) body.get("userId");
        String plan   = body == null ? null : (String) body.get("plan");
        String status = body == null ? null : (String) body.get("status");
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(
                    "Mock webhook requires {userId, plan, status}", 400);
        }

        SubscriptionRepository.Subscription existing = subscriptionRepo.findById(userId).orElse(null);
        SubscriptionRepository.Subscription updated = new SubscriptionRepository.Subscription(
                userId,
                existing != null ? existing.stripeCustomerId() : null,
                existing != null ? existing.stripeSubscriptionId() : null,
                plan != null ? plan : "family_monthly",
                status != null ? status : "active",
                Instant.now().plus(30, ChronoUnit.DAYS),
                false,
                null,
                existing != null ? existing.createdAt() : Instant.now(),
                Instant.now()
        );
        subscriptionRepo.save(updated);
        premiumService.refreshFlag(userId);

        log.info("[Subscription] MOCK webhook applied user={} status={}",
                userId, updated.status());
        return Map.of("mode", "mock", "userId", userId, "status", updated.status());
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void runHandler(Event event) {
        try {
            handleStripeEvent(event);
        } catch (Exception e) {
            log.error("[Subscription] webhook handler failed type={}: {}",
                    event.getType(), e.getMessage(), e);
        }
    }

    private void handleStripeEvent(Event event) {
        switch (event.getType()) {
            case "checkout.session.completed" -> {
                Session session = (Session) event.getDataObjectDeserializer()
                        .getObject().orElseThrow(() ->
                                new BusinessException("Stripe session payload missing", 400));
                String userId = session.getClientReferenceId();
                if (userId == null) {
                    log.warn("[Stripe] checkout.session.completed missing userId");
                    return;
                }
                SubscriptionRepository.Subscription existing =
                        subscriptionRepo.findById(userId).orElse(null);
                String plan = (session.getMetadata() != null
                        && session.getMetadata().get("plan") != null)
                        ? session.getMetadata().get("plan")
                        : (existing != null ? existing.plan() : null);

                subscriptionRepo.save(new SubscriptionRepository.Subscription(
                        userId,
                        session.getCustomer(),
                        session.getSubscription(),
                        plan,
                        "active",
                        existing != null ? existing.currentPeriodEnd() : null,
                        false,
                        null,
                        existing != null ? existing.createdAt() : Instant.now(),
                        Instant.now()
                ));
                premiumService.refreshFlag(userId);
                premiumService.convertTrial(userId);
                log.info("[Stripe] checkout.complete user={} sub={}",
                        userId, session.getSubscription());
            }
            case "customer.subscription.updated" -> {
                Subscription s = (Subscription) event.getDataObjectDeserializer()
                        .getObject().orElseThrow(() ->
                                new BusinessException("Stripe sub payload missing", 400));
                applySubscriptionUpdate(s);
            }
            case "customer.subscription.deleted" -> {
                Subscription s = (Subscription) event.getDataObjectDeserializer()
                        .getObject().orElseThrow(() ->
                                new BusinessException("Stripe sub payload missing", 400));
                String userId = subscriptionRepo
                        .findUserIdByStripeSubscriptionId(s.getId()).orElse(null);
                if (userId == null) return;
                SubscriptionRepository.Subscription sub =
                        subscriptionRepo.findById(userId).orElse(null);
                if (sub != null) {
                    subscriptionRepo.save(new SubscriptionRepository.Subscription(
                            sub.userId(), sub.stripeCustomerId(),
                            sub.stripeSubscriptionId(), sub.plan(),
                            "canceled", sub.currentPeriodEnd(),
                            false, Instant.now(),
                            sub.createdAt(), Instant.now()
                    ));
                    premiumService.evictEntitlement(userId);
                    premiumService.refreshFlag(userId);
                    log.info("[Stripe] sub.deleted user={} — premium revoked", userId);
                } else {
                    log.warn("[Stripe] sub.deleted — no subscription row found for stripeSubId={}",
                            s.getId());
                }
            }
            default -> log.debug("[Stripe] ignored event type={}", event.getType());
        }
    }

    private void applySubscriptionUpdate(Subscription s) {
        String userId = subscriptionRepo
                .findUserIdByStripeSubscriptionId(s.getId()).orElse(null);
        if (userId == null) return;
        SubscriptionRepository.Subscription sub = subscriptionRepo.findById(userId).orElse(null);
        if (sub == null) return;

        Instant periodEnd = s.getCurrentPeriodEnd() != null
                ? Instant.ofEpochSecond(s.getCurrentPeriodEnd())
                : sub.currentPeriodEnd();
        boolean cancelAtEnd = s.getCancelAtPeriodEnd() != null
                ? s.getCancelAtPeriodEnd()
                : sub.cancelAtPeriodEnd();

        if (s.getCancelAtPeriodEnd() != null && s.getCancelAtPeriodEnd()) {
            log.info("[Stripe] sub.cancel_at_period_end=true user={} ends={}", userId, periodEnd);
        }

        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                sub.userId(), sub.stripeCustomerId(), sub.stripeSubscriptionId(),
                sub.plan(), s.getStatus(), periodEnd,
                cancelAtEnd, sub.canceledAt(),
                sub.createdAt(), Instant.now()
        ));
        premiumService.refreshFlag(userId);
    }
}
