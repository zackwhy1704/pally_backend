package com.pally.api.subscription;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import com.stripe.model.Event;
import com.stripe.model.Subscription;
import com.stripe.model.checkout.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Subscription endpoints — Stripe-shaped, but degrades to a deterministic
 * mock when {@code STRIPE_SECRET_KEY} is not configured. That way the
 * Flutter client has a stable contract through pilots while real Stripe
 * wiring (Java SDK + webhook signature verification + price IDs) is
 * delivered as a focused ops follow-up.
 *
 * <p>In mock mode:
 *  - {@code POST /checkout} returns a fake {@code checkoutUrl} pointing
 *    at the Railway host (the client treats any 2xx as "open this URL").
 *  - {@code POST /webhook} accepts any JSON body and bumps the user's
 *    subscription to {@code active} for 30 days — useful for QA + demos.
 *  - {@code GET /status} returns the row from {@code subscriptions} or
 *    a synthetic {@code free} record when none exists.
 */
@RestController
@RequestMapping("/api/v1/subscription")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final SubscriptionJpaRepository subRepo;
    private final PremiumService premiumService;
    private final StripeService stripeService;
    private final ObjectMapper objectMapper;
    private final com.pally.infrastructure.persistence.subscription
            .ProcessedStripeEventJpaRepository processedEventRepo;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    private boolean isLive() {
        return stripeSecretKey != null && !stripeSecretKey.isBlank();
    }

    /// Single endpoint the Flutter app polls on resume + after returning
    /// from Stripe. Source tells the UI whether to show "renew" (SELF) vs
    /// "managed by parent" (PARENT) — both unlock premium identically.
    @GetMapping("/entitlement")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> entitlement(
            @AuthenticationPrincipal String userId) {
        PremiumService.Entitlement e = premiumService.resolve(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("isPremium", e.isPremium());
        body.put("source", e.source());
        body.put("plan", e.plan());
        body.put("status", e.status());
        body.put("trialEndsAt", e.trialEndsAt() == null ? null
                : e.trialEndsAt().toString());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/status")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal String userId) {
        SubscriptionJpaEntity sub = subRepo.findById(userId).orElse(null);
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("mode", isLive() ? "live" : "mock");
        if (sub == null) {
            body.put("status", "free");
            body.put("plan", null);
            body.put("currentPeriodEnd", null);
        } else {
            body.put("status", sub.getStatus());
            body.put("plan", sub.getPlan());
            body.put("currentPeriodEnd",
                    sub.getCurrentPeriodEnd() == null
                            ? null
                            : sub.getCurrentPeriodEnd().toString());
        }
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/checkout")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkout(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String plan = body == null ? null : body.get("plan");
        if (plan == null || plan.isBlank()) {
            throw new BusinessException("plan is required", 400);
        }
        if (!isLive()) {
            log.info("[Subscription] MOCK checkout user={} plan={}", userId, plan);
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "mode", "mock",
                    "checkoutUrl",
                    "https://pallybackend-production.up.railway.app/mock-checkout"
                            + "?plan=" + plan + "&user=" + userId,
                    "plan", plan)));
        }
        String url = stripeService.createCheckoutSession(userId, plan);
        log.info("[Subscription] LIVE checkout user={} plan={}", userId, plan);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "mode", "live",
                "checkoutUrl", url,
                "plan", plan)));
    }

    /// Opens a Stripe Billing Portal session — manage / cancel / swap card.
    /// Requires the user to already have a stripe_customer_id; otherwise we
    /// 409 so the client can route to checkout instead.
    @PostMapping("/portal")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> portal(
            @AuthenticationPrincipal String userId) {
        if (!isLive()) {
            // Mock-mode portal: return a placeholder URL so the client flow
            // still works in the pilot. Pally never bills in mock mode.
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "mode", "mock",
                    "url",
                    "https://pallybackend-production.up.railway.app/mock-portal")));
        }
        SubscriptionJpaEntity sub = subRepo.findById(userId).orElseThrow(
                () -> new BusinessException(
                        "Subscribe first before opening portal", 409));
        String url = stripeService.createPortalSession(sub.getStripeCustomerId());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "mode", "live",
                "url", url)));
    }

    /// Webhook endpoint. Takes the RAW request body as String so the live
    /// branch can verify Stripe's signature byte-for-byte; the mock branch
    /// parses it as JSON for QA flows. Returns 200 fast so Stripe doesn't
    /// retry; transient handler errors are logged but don't propagate.
    @PostMapping("/webhook")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false)
                    String sigHeader) {
        boolean live = isLive() && sigHeader != null && !sigHeader.isBlank();
        if (!live) {
            return handleMockWebhook(rawBody);
        }
        Event event = stripeService.verifyWebhook(rawBody, sigHeader);
        // D2 — insert-first idempotency. The old check-then-act could let
        // two concurrent re-deliveries both pass existsById before either
        // recorded. We now attempt the INSERT first; the PK collision is
        // the race-safe dedupe signal. Only if the INSERT succeeded do we
        // run the handler.
        if (event.getId() == null || event.getId().isBlank()) {
            log.warn("[Subscription] webhook event missing id type={} — running",
                    event.getType());
            return runHandler(event);
        }
        boolean firstDelivery;
        try {
            firstDelivery = claimEvent(event.getId(), event.getType());
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            // Belt-and-braces — claimEvent already catches this, but if a
            // newer Hibernate version surfaces the violation differently
            // we still want to dedupe.
            firstDelivery = false;
        }
        if (!firstDelivery) {
            log.info("[Subscription] webhook duplicate event={} type={} — skipping",
                    event.getId(), event.getType());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "mode", "live",
                    "type", event.getType(),
                    "id", event.getId(),
                    "duplicate", true)));
        }
        try {
            handleStripeEvent(event);
        } catch (Exception e) {
            // Handler failed AFTER we claimed the event. Delete the claim
            // row so Stripe's next retry can re-attempt. Without this,
            // the next delivery would see {@code duplicate=true} and skip
            // — the state mutation would be lost forever.
            log.error("[Subscription] webhook handler failed type={}: {} "
                    + "— releasing claim for retry", event.getType(),
                    e.getMessage(), e);
            try {
                processedEventRepo.deleteById(event.getId());
            } catch (Exception releaseErr) {
                log.warn("[Subscription] failed to release event claim {}: {}",
                        event.getId(), releaseErr.getMessage());
            }
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "mode", "live",
                "type", event.getType(),
                "id", event.getId())));
    }

    /// Attempts to insert the event-id row. Returns true if this is the
    /// FIRST time we've seen this event (i.e. the row didn't exist);
    /// false if the PK collision says another thread / re-delivery already
    /// claimed it. This is the race-safe equivalent of check-then-act.
    private boolean claimEvent(String eventId, String eventType) {
        try {
            var row = new com.pally.infrastructure.persistence.subscription
                    .ProcessedStripeEventJpaEntity();
            row.setEventId(eventId);
            row.setEventType(eventType == null ? "unknown" : eventType);
            row.setProcessedAt(Instant.now());
            processedEventRepo.saveAndFlush(row);
            return true;
        } catch (org.springframework.dao.DataIntegrityViolationException dup) {
            return false;
        }
    }

    /// Falls back path for events without an id — just runs the handler
    /// without dedupe (events lacking an id are not retried by Stripe).
    private ResponseEntity<ApiResponse<Map<String, Object>>> runHandler(Event event) {
        try {
            handleStripeEvent(event);
        } catch (Exception e) {
            log.error("[Subscription] webhook handler failed type={}: {}",
                    event.getType(), e.getMessage(), e);
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "mode", "live",
                "type", event.getType(),
                "id", event.getId() == null ? "" : event.getId())));
    }

    private void handleStripeEvent(Event event) {
        switch (event.getType()) {
            case "checkout.session.completed" -> {
                Session session = (Session) event.getDataObjectDeserializer()
                        .getObject().orElseThrow(() ->
                                new BusinessException(
                                        "Stripe session payload missing", 400));
                String userId = session.getClientReferenceId();
                if (userId == null) {
                    log.warn("[Stripe] checkout.session.completed missing userId");
                    return;
                }
                SubscriptionJpaEntity sub = subRepo.findById(userId)
                        .orElseGet(() -> {
                            SubscriptionJpaEntity created = new SubscriptionJpaEntity();
                            created.setUserId(userId);
                            return created;
                        });
                sub.setStripeCustomerId(session.getCustomer());
                sub.setStripeSubscriptionId(session.getSubscription());
                sub.setStatus("active");
                if (session.getMetadata() != null
                        && session.getMetadata().get("plan") != null) {
                    sub.setPlan(session.getMetadata().get("plan"));
                }
                sub.setUpdatedAt(Instant.now());
                subRepo.save(sub);
                premiumService.refreshFlag(userId);
                log.info("[Stripe] checkout.complete user={} sub={}",
                        userId, sub.getStripeSubscriptionId());
            }
            case "customer.subscription.updated" -> {
                Subscription s = (Subscription) event.getDataObjectDeserializer()
                        .getObject().orElseThrow(() ->
                                new BusinessException(
                                        "Stripe sub payload missing", 400));
                applySubscriptionUpdate(s);
            }
            case "customer.subscription.deleted" -> {
                Subscription s = (Subscription) event.getDataObjectDeserializer()
                        .getObject().orElseThrow(() ->
                                new BusinessException(
                                        "Stripe sub payload missing", 400));
                String userId = findUserBySubscriptionId(s.getId());
                if (userId == null) return;
                SubscriptionJpaEntity sub = subRepo.findById(userId).orElse(null);
                if (sub != null) {
                    sub.setStatus("canceled");
                    sub.setUpdatedAt(Instant.now());
                    subRepo.save(sub);
                    premiumService.refreshFlag(userId);
                }
                log.info("[Stripe] sub.deleted user={}", userId);
            }
            default -> log.debug("[Stripe] ignored event type={}", event.getType());
        }
    }

    private void applySubscriptionUpdate(Subscription s) {
        String userId = findUserBySubscriptionId(s.getId());
        if (userId == null) return;
        SubscriptionJpaEntity sub = subRepo.findById(userId).orElse(null);
        if (sub == null) return;
        sub.setStatus(s.getStatus());
        if (s.getCurrentPeriodEnd() != null) {
            sub.setCurrentPeriodEnd(Instant.ofEpochSecond(s.getCurrentPeriodEnd()));
        }
        sub.setUpdatedAt(Instant.now());
        subRepo.save(sub);
        premiumService.refreshFlag(userId);
    }

    /// Stripe identifies subscriptions by their own id, not by our userId.
    /// We persist the mapping on checkout.session.completed so this lookup
    /// stays a primary-key scan.
    private String findUserBySubscriptionId(String stripeSubId) {
        if (stripeSubId == null) return null;
        return subRepo.findAll().stream()
                .filter(r -> stripeSubId.equals(r.getStripeSubscriptionId()))
                .map(SubscriptionJpaEntity::getUserId)
                .findFirst().orElse(null);
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> handleMockWebhook(
            String rawBody) {
        Map<String, Object> body;
        try {
            JsonNode node = objectMapper.readTree(
                    rawBody == null ? "{}" : rawBody);
            body = objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            throw new BusinessException(
                    "Mock webhook body must be valid JSON", 400);
        }
        String userId = body == null ? null : (String) body.get("userId");
        String plan = body == null ? null : (String) body.get("plan");
        String status = body == null ? null : (String) body.get("status");
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(
                    "Mock webhook requires {userId, plan, status}", 400);
        }
        SubscriptionJpaEntity sub = subRepo.findById(userId).orElseGet(() -> {
            SubscriptionJpaEntity created = new SubscriptionJpaEntity();
            created.setUserId(userId);
            return created;
        });
        sub.setStatus(status != null ? status : "active");
        sub.setPlan(plan != null ? plan : "family_monthly");
        sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        sub.setUpdatedAt(Instant.now());
        subRepo.save(sub);
        premiumService.refreshFlag(userId);
        log.info("[Subscription] MOCK webhook applied user={} status={}",
                userId, sub.getStatus());
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "mode", "mock",
                "userId", userId,
                "status", sub.getStatus())));
    }
}
