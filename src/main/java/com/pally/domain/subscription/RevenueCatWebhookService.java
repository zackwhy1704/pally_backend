package com.pally.domain.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Handles RevenueCat subscription webhooks for mobile IAP (Apple StoreKit + Google
 * Play Billing). RevenueCat's {@code app_user_id} is our userId, so events map
 * straight onto the same {@code subscriptions} table the Stripe path writes, and
 * {@link PremiumService} stays the single source of truth for entitlement.
 *
 * <p>Auth: RevenueCat sends the exact value configured in its dashboard's
 * Authorization header field; we compare it (constant-time) to
 * {@code app.revenuecat.auth-secret}. When the secret is unset, every call is
 * rejected (fail closed) so the endpoint can't be hit anonymously.
 */
@Service
@Slf4j
public class RevenueCatWebhookService {

    /// Events that mean the user currently HAS access.
    private static final Set<String> GRANT_TYPES = Set.of(
            "INITIAL_PURCHASE", "RENEWAL", "PRODUCT_CHANGE",
            "NON_RENEWING_PURCHASE", "UNCANCELLATION");

    /// Events that mean access has ENDED. (CANCELLATION = auto-renew off but access
    /// continues until EXPIRATION; BILLING_ISSUE = grace period — neither downgrades.)
    private static final Set<String> EXPIRE_TYPES = Set.of("EXPIRATION");

    private final SubscriptionRepository subscriptionRepo;
    private final PremiumService premiumService;
    private final ObjectMapper objectMapper;
    private final String authSecret;

    public RevenueCatWebhookService(SubscriptionRepository subscriptionRepo,
                                    PremiumService premiumService,
                                    ObjectMapper objectMapper,
                                    @Value("${app.revenuecat.auth-secret:}") String authSecret) {
        this.subscriptionRepo = subscriptionRepo;
        this.premiumService = premiumService;
        this.objectMapper = objectMapper;
        this.authSecret = authSecret;
    }

    /** Constant-time check of the Authorization header against the configured secret. */
    public boolean isAuthorized(String authHeader) {
        if (authSecret == null || authSecret.isBlank() || authHeader == null) {
            return false; // unconfigured → fail closed
        }
        return MessageDigest.isEqual(
                authHeader.getBytes(StandardCharsets.UTF_8),
                authSecret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Applies a RevenueCat event to the user's subscription. Never throws on a
     * malformed body — logs and acks so RevenueCat doesn't spin on retries
     * (matching the Stripe handler's policy).
     */
    @Transactional
    public Map<String, Object> handle(String rawBody) {
        try {
            JsonNode event = objectMapper.readTree(rawBody).path("event");
            String type = event.path("type").asText("");
            String userId = event.path("app_user_id").asText(null);
            if (userId == null || userId.isBlank()) {
                return result(false, type, "missing app_user_id");
            }

            if (GRANT_TYPES.contains(type)) {
                String plan = planFromEvent(event);
                Instant periodEnd = event.hasNonNull("expiration_at_ms")
                        ? Instant.ofEpochMilli(event.get("expiration_at_ms").asLong())
                        : null;
                grant(userId, plan, periodEnd);
                premiumService.refreshFlag(userId);
                log.info("[RevenueCat] {} → grant user={} plan={} until={}", type, userId, plan, periodEnd);
            } else if (EXPIRE_TYPES.contains(type)) {
                downgrade(userId);
                premiumService.evictEntitlement(userId);
                premiumService.refreshFlag(userId);
                log.info("[RevenueCat] {} → downgrade user={}", type, userId);
            } else {
                log.info("[RevenueCat] {} → no-op user={}", type, userId);
            }
            return result(true, type, userId);
        } catch (Exception e) {
            log.warn("[RevenueCat] webhook handling failed: {}", e.getMessage());
            return result(false, "", "handler-error");
        }
    }

    /// Derives the plan from the product id (stores should encode the tier, e.g.
    /// "apalchi_max_monthly"). Falls back to "pro".
    private String planFromEvent(JsonNode event) {
        String pid = event.path("product_id").asText("").toLowerCase();
        if (pid.contains("max")) return "max";
        if (pid.contains("family")) return "family";
        return "pro";
    }

    private void grant(String userId, String plan, Instant periodEnd) {
        SubscriptionRepository.Subscription existing = subscriptionRepo.findById(userId).orElse(null);
        Instant createdAt = existing != null ? existing.createdAt() : Instant.now();
        String customerId = existing != null ? existing.stripeCustomerId() : null;
        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                userId, customerId, null, plan, "active", periodEnd,
                false, null, createdAt, Instant.now()));
    }

    private void downgrade(String userId) {
        SubscriptionRepository.Subscription s = subscriptionRepo.findById(userId).orElse(null);
        if (s == null) return;
        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                s.userId(), s.stripeCustomerId(), s.stripeSubscriptionId(),
                s.plan(), "free", s.currentPeriodEnd(),
                s.cancelAtPeriodEnd(), Instant.now(), s.createdAt(), Instant.now()));
    }

    private Map<String, Object> result(boolean handled, String type, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", handled);
        m.put("type", type);
        m.put("detail", detail);
        return m;
    }
}
