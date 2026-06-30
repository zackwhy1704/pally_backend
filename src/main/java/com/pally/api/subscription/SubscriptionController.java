package com.pally.api.subscription;

import com.pally.api.subscription.dto.UpgradeLinkRequest;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionManagementService;
import com.pally.domain.subscription.UpgradeLinkService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Subscription endpoints — Stripe-backed, degrades gracefully to a mock
 * when {@code STRIPE_SECRET_KEY} is not set (useful for local dev / QA).
 *
 * <p>In live mode, {@code POST /webhook} verifies Stripe's HMAC signature
 * via {@code handleLiveWebhook} before processing any event.
 * In mock mode (no {@code Stripe-Signature} header), {@code X-Mock-Secret}
 * is required; on match, {@code handleMockWebhook} bumps the subscription
 * to {@code active} for 30 days without touching Stripe.
 *
 * <p>{@code POST /checkout} always returns a {@code checkoutUrl}; in mock
 * mode this is a synthetic URL that the client can open to simulate a
 * completed purchase.
 */
@RestController
@RequestMapping("/api/v1/subscription")
@RequiredArgsConstructor
@Slf4j
public class SubscriptionController {

    private final SubscriptionManagementService subscriptionService;
    private final PremiumService                premiumService;
    private final UpgradeLinkService            upgradeLinkService;

    /// HTTPS landing page for Stripe's success/cancel redirect.
    @GetMapping("/return")
    public ResponseEntity<Void> handleReturn(
            @RequestParam(name = "status", defaultValue = "success") String status) {
        String deepLink = "pally://subscription/return?status=" + status;
        log.info("[Subscription] Stripe return redirect → {}", deepLink);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.LOCATION, deepLink);
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }

    @GetMapping("/entitlement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> entitlement(
            @AuthenticationPrincipal String userId) {
        PremiumService.Entitlement e = premiumService.resolve(userId);
        Map<String, Object> body = new HashMap<>();
        body.put("isPremium", e.isPremium());
        body.put("source", e.source());
        body.put("plan", e.plan());
        body.put("status", e.status());
        body.put("trialEndsAt", e.trialEndsAt() == null ? null : e.trialEndsAt().toString());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> status(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(subscriptionService.getStatus(userId)));
    }

    @PostMapping("/checkout")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkout(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String plan = body == null ? null : body.get("plan");
        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.createCheckout(userId, plan)));
    }

    @PostMapping("/portal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> portal(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.createPortal(userId)));
    }

    /// Emails and/or push-notifies the authenticated user the web billing link
    /// so they can finish their upgrade in a browser. Body is optional; a null
    /// or omitted {@code channels} defaults to BOTH email and push.
    /// Response: {@code { "sent": { "email": <bool>, "push": <bool> } }}.
    @PostMapping("/upgrade-link")
    public ResponseEntity<ApiResponse<Map<String, Object>>> upgradeLink(
            @AuthenticationPrincipal String userId,
            @RequestBody(required = false) UpgradeLinkRequest body) {
        return ResponseEntity.ok(ApiResponse.success(
                upgradeLinkService.sendUpgradeLink(
                        userId, body == null ? null : body.channels())));
    }

    /// Webhook endpoint. Takes the RAW request body as String so the live
    /// branch can verify Stripe's signature byte-for-byte; the mock branch
    /// parses it as JSON for QA flows. Returns 200 fast so Stripe doesn't
    /// retry; transient handler errors are logged but don't propagate.
    @PostMapping("/webhook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "Stripe-Signature", required = false) String sigHeader,
            @RequestHeader(value = "X-Mock-Secret", required = false) String mockSecret) {

        if (sigHeader == null || sigHeader.isBlank()) {
            if (!subscriptionService.isMockAllowed(mockSecret)) {
                log.warn("[Subscription] Unauthenticated webhook call rejected — "
                        + "no Stripe-Signature and no valid X-Mock-Secret");
                return ResponseEntity.status(401)
                        .body(ApiResponse.error("Webhook authentication required", 401));
            }
            return ResponseEntity.ok(ApiResponse.success(
                    subscriptionService.handleMockWebhook(rawBody)));
        }

        return ResponseEntity.ok(ApiResponse.success(
                subscriptionService.handleLiveWebhook(rawBody, sigHeader)));
    }
}
