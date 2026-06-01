package com.pally.api.usage;

import com.pally.domain.progress.LevelRewards;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
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
 * Surfaces today's chat usage + trial/entitlement info so the Flutter UI
 * can drive countdown banners and gate messaging without computing
 * entitlement client-side.
 */
@RestController
@RequestMapping("/api/v1/usage")
@RequiredArgsConstructor
public class UsageController {

    private final ChatRateLimiter chatRateLimiter;
    private final PremiumService premiumService;
    private final UserJpaRepository userRepo;

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> today(
            @AuthenticationPrincipal String userId) {

        PremiumService.Entitlement ent;
        try {
            ent = premiumService.resolve(userId);
        } catch (Exception ignored) {
            ent = new PremiumService.Entitlement(false, "NONE", null, "free", null);
        }

        boolean premium = ent.isPremium();
        Map<String, Object> body = new HashMap<>();
        body.put("isPremium", premium);
        body.put("source", ent.source());
        body.put("date", LocalDate.now(ZoneOffset.UTC).toString());

        // Trial info — always expose so the countdown banner can show
        // even when the user is currently premium via trial.
        PremiumService.TrialInfo trial = premiumService.getTrialInfo(userId);
        body.put("trialActive",    trial.trialActive());
        body.put("trialStatus",    trial.trialStatus());
        body.put("trialEndsAt",    trial.trialEndsAt() != null ? trial.trialEndsAt().toString() : null);
        body.put("trialDaysLeft",  trial.trialDaysLeft());
        body.put("trialHoursLeft", trial.trialHoursLeft());

        // Free-tier cap: level 5+ unlocks a second Mochi slot.
        int userLevel = userRepo.findById(userId)
                .map(u -> u.getLevel() > 0 ? u.getLevel() : 1)
                .orElse(1);
        body.put("freeTutorCap", premium ? null : LevelRewards.freeTutorCap(userLevel));

        if (premium) {
            body.put("chatUsed", 0);
            body.put("chatLimit", null);
            body.put("chatRemaining", null);
        } else {
            int used  = chatRateLimiter.dailyHitsToday(userId);
            int limit = ChatRateLimiter.FREE_DAILY_LIMIT;
            body.put("chatUsed", used);
            body.put("chatLimit", limit);
            body.put("chatRemaining", Math.max(0, limit - used));
        }
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
