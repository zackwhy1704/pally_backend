package com.pally.api.subscription;

import com.pally.domain.subscription.RevenueCatWebhookService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thin RevenueCat webhook endpoint (mobile IAP). Public (server-to-server, no user
 * JWT) but gated by the shared Authorization secret inside the service.
 */
@RestController
@RequestMapping("/api/v1/subscription")
@RequiredArgsConstructor
@Slf4j
public class RevenueCatWebhookController {

    private final RevenueCatWebhookService revenueCatWebhookService;

    @PostMapping("/revenuecat-webhook")
    public ResponseEntity<ApiResponse<Map<String, Object>>> webhook(
            @RequestBody String rawBody,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        if (!revenueCatWebhookService.isAuthorized(authHeader)) {
            log.warn("[RevenueCat] webhook rejected — missing/invalid Authorization");
            return ResponseEntity.status(401)
                    .body(ApiResponse.error("Webhook authentication required", 401));
        }
        return ResponseEntity.ok(ApiResponse.success(revenueCatWebhookService.handle(rawBody)));
    }
}
