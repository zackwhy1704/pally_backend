package com.pally.api.subscription;

import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        // Real Stripe wiring lands in a follow-up. Returning 501 here is more
        // honest than half-pretending the call succeeded.
        throw new BusinessException(
                "Live Stripe checkout not yet wired — ops follow-up", 501);
    }

    @PostMapping("/webhook")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> webhook(
            @RequestBody Map<String, Object> body) {
        if (!isLive()) {
            String userId = body == null ? null : (String) body.get("userId");
            String plan = body == null ? null : (String) body.get("plan");
            String status = body == null ? null : (String) body.get("status");
            if (userId == null || userId.isBlank()) {
                throw new BusinessException(
                        "Mock webhook requires {userId, plan, status}", 400);
            }
            SubscriptionJpaEntity sub =
                    subRepo.findById(userId).orElseGet(() -> {
                        SubscriptionJpaEntity created = new SubscriptionJpaEntity();
                        created.setUserId(userId);
                        return created;
                    });
            sub.setStatus(status != null ? status : "active");
            sub.setPlan(plan != null ? plan : "family_monthly");
            sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
            sub.setUpdatedAt(Instant.now());
            subRepo.save(sub);
            log.info("[Subscription] MOCK webhook applied user={} status={}",
                    userId, sub.getStatus());
            return ResponseEntity.ok(ApiResponse.success(Map.of(
                    "mode", "mock",
                    "userId", userId,
                    "status", sub.getStatus())));
        }
        throw new BusinessException(
                "Live Stripe webhook not yet wired — ops follow-up", 501);
    }
}
