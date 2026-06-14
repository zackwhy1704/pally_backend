package com.pally.api.family;

import com.pally.domain.account.AccountType;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Family account endpoints — link children to a parent, list linked children,
 * and enforce per-tier child caps.
 *
 * <p>Any user with accountType=PARENT can manage family links. Free users
 * are capped at 1 child; FAMILY tier at 4; CENTRE at 15.
 */
@RestController
@RequestMapping("/api/v1/family")
@RequiredArgsConstructor
@Slf4j
public class FamilyController {

    private final UserJpaRepository userRepo;
    private final PremiumService premiumService;
    private final ActivityLogJpaRepository activityRepo;

    /**
     * Returns the list of child accounts linked to the authenticated parent.
     * Any PARENT account can call this. Enriches children with stats.
     */
    @GetMapping("/children")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listChildren(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity parent = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (parent.getAccountType() != AccountType.PARENT) {
            return ResponseEntity.status(403)
                    .body(ApiResponse.error("Only PARENT accounts can list children", 403));
        }
        List<UserJpaEntity> children = userRepo.findByParentId(userId);
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        List<Map<String, Object>> result = children.stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("userId", c.getId());
                    m.put("displayName", c.getDisplayName() != null ? c.getDisplayName() : "Child");
                    m.put("email", c.getEmail());
                    m.put("accountType", c.getAccountType());
                    m.put("joinedAt", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
                    // Enrichment fields
                    m.put("streakDays", c.getStreakDays());
                    m.put("level", c.getLevel());
                    m.put("lastActiveDate", c.getLastActiveDate() != null
                            ? c.getLastActiveDate().toString() : null);
                    int minutesThisWeek = orZero(activityRepo.sumMinutesBetween(
                            c.getId(), weekAgo, Instant.now()));
                    m.put("minutesThisWeek", minutesThisWeek);
                    m.put("statusChip", computeStatusChip(c));
                    return m;
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // NOTE: The parent-generates / child-joins link flow that used to live here
    // (POST /generate-link-code + POST /join/{linkCode}) was REMOVED. The single
    // canonical parent-link flow is child-generates / parent-claims, implemented
    // in AccountController (POST /account/link-code + POST /account/claim). Having
    // both wrote the same columns (linkCode / parentId) with opposite ownership
    // and different TTLs — a real contradiction. Only getChildren remains here.

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Compute status chip for a child based on recent activity and mastery.
     */
    private String computeStatusChip(UserJpaEntity child) {
        LocalDate lastActive = child.getLastActiveDate();
        long daysInactive = lastActive == null
                ? 999
                : java.time.temporal.ChronoUnit.DAYS.between(lastActive, LocalDate.now());

        if (daysInactive >= 6) return "needs_attention";
        if (daysInactive >= 4) return "behind";
        return "on_track";
    }

    /**
     * Resolve child cap based on subscription tier. Free/PRO/MAX users get 1.
     */
    private int resolveChildCap(SubscriptionTier tier) {
        return switch (tier) {
            case FREE, PRO, MAX -> 1;
            case FAMILY -> 4;
            case CENTRE -> 15;
        };
    }

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
