package com.pally.api.usage;

import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.ratelimit.ChatRateLimiter;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

/**
 * Surfaces today's chat usage so the Flutter chat UI can show "N left"
 * <i>before</i> the 402/429 wall. The wall itself stays in
 * {@link ChatRateLimiter} — this endpoint is purely informational.
 *
 * <p>Premium users get {@code chatLimit = null} to signal "unlimited" so
 * the client never wastes pixels showing a count for them.
 */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final ChatRateLimiter chatRateLimiter;
    private final PremiumService premiumService;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> today(
            @AuthenticationPrincipal String userId) {
        boolean premium;
        try {
            premium = premiumService.resolve(userId).isPremium();
        } catch (Exception ignored) {
            // Mirror ChatRateLimiter: if the premium check blips, don't
            // block the UX — fall through to free-tier surfacing.
            premium = false;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("isPremium", premium);
        body.put("date", LocalDate.now(ZoneOffset.UTC).toString());

        if (premium) {
            body.put("chatUsed", 0);
            body.put("chatLimit", null);
            body.put("chatRemaining", null);
        } else {
            int used = chatRateLimiter.dailyHitsToday(userId);
            int limit = ChatRateLimiter.FREE_DAILY_LIMIT;
            body.put("chatUsed", used);
            body.put("chatLimit", limit);
            body.put("chatRemaining", Math.max(0, limit - used));
        }
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
