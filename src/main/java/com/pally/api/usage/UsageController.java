package com.pally.api.usage;

import com.pally.domain.progress.LevelRewards;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionLimits;
import com.pally.domain.subscription.SubscriptionTier;
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
        SubscriptionTier tier;
        try {
            tier = premiumService.resolveTier(userId);
        } catch (Exception ignored) {
            tier = SubscriptionTier.FREE;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("isPremium", premium);
        body.put("source", ent.source());
        body.put("subscriptionTier", tier.name());
        body.put("date", LocalDate.now(com.pally.shared.util.PallyTime.SGT).toString());

        // Trial info — always expose so the countdown banner can show
        // even when the user is currently premium via trial.
        PremiumService.TrialInfo trial = premiumService.getTrialInfo(userId);
        body.put("trialActive",    trial.trialActive());
        body.put("trialStatus",    trial.trialStatus());
        body.put("trialEndsAt",    trial.trialEndsAt() != null ? trial.trialEndsAt().toString() : null);
        body.put("trialDaysLeft",  trial.trialDaysLeft());
        body.put("trialHoursLeft", trial.trialHoursLeft());

        // Tutor cap + slot cooldown info
        var userEntityOpt = userRepo.findById(userId);
        int userLevel = userEntityOpt.map(u -> u.getLevel() > 0 ? u.getLevel() : 1).orElse(1);
        body.put("freeTutorCap", premium ? null : LevelRewards.freeTutorCap(userLevel));
        int rawMochiCap = SubscriptionLimits.mochiCap(tier, userLevel);
        body.put("mochiCap", rawMochiCap == Integer.MAX_VALUE ? -1 : rawMochiCap);

        // Slot swap cooldown: expose seconds remaining so Flutter can show a timer.
        // For premium users this is always 0 (no cooldown).
        long slotCooldownSecondsRemaining = 0;
        if (!premium) {
            java.time.Instant lastChange = userEntityOpt
                    .map(u -> u.getLastSlotChangeAt())
                    .orElse(null);
            if (lastChange != null) {
                long elapsed = java.time.Duration.between(lastChange, java.time.Instant.now()).getSeconds();
                long cooldownSecs = 24L * 3600;
                slotCooldownSecondsRemaining = Math.max(0, cooldownSecs - elapsed);
            }
        }
        body.put("slotCooldownSecondsRemaining", slotCooldownSecondsRemaining);

        int chatUsed  = chatRateLimiter.dailyHitsToday(userId);
        int chatLimit = switch (tier) {
            case FREE                -> ChatRateLimiter.FREE_DAILY_LIMIT;
            case PRO                 -> ChatRateLimiter.PRO_DAILY_LIMIT;
            case MAX, FAMILY, CENTRE -> -1; // unlimited
        };
        body.put("chatUsed", chatUsed);
        body.put("chatLimit", chatLimit);
        body.put("chatRemaining", chatLimit == -1 ? -1 : Math.max(0, chatLimit - chatUsed));
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
