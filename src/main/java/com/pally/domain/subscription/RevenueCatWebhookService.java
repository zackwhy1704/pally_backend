package com.pally.domain.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
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
    /// EXPIRATION = access period actually ended. REFUND = the purchase was
    /// reversed, so access must end NOW rather than at period end — it was
    /// previously unhandled, which meant a refunded purchase kept access forever.
    private static final Set<String> EXPIRE_TYPES = Set.of("EXPIRATION", "REFUND");

    /// A purchase moving between app_user_ids (RevenueCat merges/transfers
    /// identities). We key entitlement on OUR userId, so a transfer landing on the
    /// wrong account is a correctness bug: the payer silently loses access and a
    /// stranger gains it. Handled explicitly rather than falling into the no-op
    /// branch, where it was invisible.
    private static final Set<String> TRANSFER_TYPES = Set.of("TRANSFER", "SUBSCRIBER_ALIAS");

    /// Payment failed but the subscription is in RevenueCat's grace period —
    /// access is NOT lost yet. Logged and stamped, never revoked; revoking here
    /// would cut off a paying user over a card that is about to retry.
    private static final Set<String> GRACE_TYPES = Set.of("BILLING_ISSUE");

    private final SubscriptionRepository subscriptionRepo;
    private final PremiumService premiumService;
    private final ProcessedRevenueCatEventRepository processedEventRepo;
    private final ObjectMapper objectMapper;
    private final String authSecret;

    public RevenueCatWebhookService(SubscriptionRepository subscriptionRepo,
                                    PremiumService premiumService,
                                    ProcessedRevenueCatEventRepository processedEventRepo,
                                    ObjectMapper objectMapper,
                                    @Value("${app.revenuecat.auth-secret:}") String authSecret) {
        this.subscriptionRepo = subscriptionRepo;
        this.premiumService = premiumService;
        this.processedEventRepo = processedEventRepo;
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

            // Idempotency: claim the RC event id before applying. A re-delivery
            // (RevenueCat retries) collides on the PK → short-circuit and ack so
            // the entitlement is never applied twice. Mirrors the Stripe path.
            String eventId = event.path("id").asText(null);
            boolean guarded = eventId != null && !eventId.isBlank();
            if (guarded) {
                boolean firstDelivery;
                try {
                    firstDelivery = processedEventRepo.claimEvent(eventId, type, Instant.now());
                } catch (DataIntegrityViolationException dup) {
                    firstDelivery = false;
                }
                if (!firstDelivery) {
                    log.info("[RevenueCat] duplicate event={} type={} — skipping", eventId, type);
                    return result(true, type, "duplicate");
                }
            } else {
                log.warn("[RevenueCat] event has no id — processing without idempotency guard (type={})", type);
            }

            try {
                applyEvent(type, userId, event);
            } catch (Exception applyErr) {
                // Release the claim so RevenueCat's next retry can reprocess.
                if (guarded) {
                    try { processedEventRepo.releaseEvent(eventId); }
                    catch (Exception relErr) {
                        log.warn("[RevenueCat] failed to release claim {}: {}", eventId, relErr.getMessage());
                    }
                }
                log.error("[RevenueCat] handler failed type={}: {}", type, applyErr.getMessage());
                return result(false, type, "handler-error");
            }
            return result(true, type, userId);
        } catch (Exception e) {
            log.warn("[RevenueCat] webhook handling failed: {}", e.getMessage());
            return result(false, "", "handler-error");
        }
    }

    /** Applies a (deduplicated) event to the user's subscription state. */
    private void applyEvent(String type, String userId, JsonNode event) {
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
        } else if (TRANSFER_TYPES.contains(type)) {
            applyTransfer(type, userId, event);
        } else if (GRACE_TYPES.contains(type)) {
            // Grace period: confirm we still know the state, do NOT change it.
            stampVerified(userId);
            premiumService.evictEntitlement(userId);
            log.warn("[RevenueCat] {} → billing issue for user={}; access retained "
                    + "during grace, entitlement re-stamped not revoked", type, userId);
        } else if ("CANCELLATION".equals(type)) {
            // DELIBERATELY IGNORED. In RevenueCat, CANCELLATION means auto-renew was
            // turned off — NOT that access ended. Access ends at EXPIRATION. Revoking
            // here would cut off someone who has paid through their period end.
            stampVerified(userId);
            log.info("[RevenueCat] CANCELLATION → auto-renew off for user={}; "
                    + "access retained until EXPIRATION", userId);
        } else {
            log.info("[RevenueCat] {} → no-op user={}", type, userId);
        }
    }

    /// Derives the plan from the product id (stores encode the tier, e.g.
    /// "apalchi_max_monthly"). An UNRECOGNISED product must never silently grant a
    /// mid/high tier — it logs a warning and falls back to the lowest paid tier
    /// ("pro"), so a typo/new product can't accidentally hand out Max or Family.
    private String planFromEvent(JsonNode event) {
        String pid = event.path("product_id").asText("").toLowerCase();
        if (pid.contains("max")) return "max";
        if (pid.contains("family")) return "family";
        if (pid.contains("pro")) return "pro";
        log.warn("[RevenueCat] unrecognised product_id='{}' — defaulting to lowest paid tier 'pro'",
                event.path("product_id").asText(""));
        return "pro";
    }

    /**
     * Moves entitlement between app_user_ids. RevenueCat sends the origin and
     * destination ids; we downgrade the origin and grant the destination so the
     * entitlement follows the purchase rather than being duplicated across both.
     *
     * <p>If either id is missing we change NOTHING and log loudly: a half-applied
     * transfer is worse than an unapplied one, because it silently revokes a
     * paying user while granting nobody.
     */
    private void applyTransfer(String type, String fallbackUserId, JsonNode event) {
        String from = firstId(event, "transferred_from");
        String to   = firstId(event, "transferred_to");
        if (from == null || to == null) {
            log.error("[RevenueCat] {} for user={} missing transferred_from/to — "
                    + "NOTHING changed (a half-applied transfer revokes a payer)",
                    type, fallbackUserId);
            return;
        }
        downgrade(from);
        premiumService.evictEntitlement(from);
        premiumService.refreshFlag(from);

        SubscriptionRepository.Subscription origin = subscriptionRepo.findById(from).orElse(null);
        String plan = origin != null ? origin.plan() : "pro";
        Instant periodEnd = origin != null ? origin.currentPeriodEnd() : null;
        grant(to, plan, periodEnd);
        premiumService.evictEntitlement(to);
        premiumService.refreshFlag(to);
        log.warn("[RevenueCat] {} → entitlement moved {} → {} (plan={})", type, from, to, plan);
    }

    /** First element of an array claim (RevenueCat sends these as arrays). */
    private static String firstId(JsonNode event, String field) {
        JsonNode n = event.path(field);
        if (n.isArray() && n.size() > 0) return n.get(0).asText(null);
        String v = n.asText(null);
        return (v == null || v.isBlank()) ? null : v;
    }

    /** Records that we re-confirmed this row against RevenueCat, without changing it. */
    private void stampVerified(String userId) {
        SubscriptionRepository.Subscription s = subscriptionRepo.findById(userId).orElse(null);
        if (s == null) return;
        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                s.userId(), s.stripeCustomerId(), s.stripeSubscriptionId(),
                s.plan(), s.status(), s.currentPeriodEnd(),
                s.cancelAtPeriodEnd(), s.canceledAt(), s.createdAt(), Instant.now(),
                Instant.now()));
    }

    private void grant(String userId, String plan, Instant periodEnd) {
        SubscriptionRepository.Subscription existing = subscriptionRepo.findById(userId).orElse(null);
        Instant createdAt = existing != null ? existing.createdAt() : Instant.now();
        String customerId = existing != null ? existing.stripeCustomerId() : null;
        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                userId, customerId, null, plan, "active", periodEnd,
                false, null, createdAt, Instant.now(), Instant.now()));
    }

    private void downgrade(String userId) {
        SubscriptionRepository.Subscription s = subscriptionRepo.findById(userId).orElse(null);
        if (s == null) return;
        subscriptionRepo.save(new SubscriptionRepository.Subscription(
                s.userId(), s.stripeCustomerId(), s.stripeSubscriptionId(),
                s.plan(), "free", s.currentPeriodEnd(),
                s.cancelAtPeriodEnd(), Instant.now(), s.createdAt(), Instant.now(),
                Instant.now()));
    }

    private Map<String, Object> result(boolean handled, String type, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("handled", handled);
        m.put("type", type);
        m.put("detail", detail);
        return m;
    }
}
