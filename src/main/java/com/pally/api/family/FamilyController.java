package com.pally.api.family;

import com.pally.domain.subscription.Entitlements;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionLimits;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.UpgradeRequiredException;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
        if (!"PARENT".equals(parent.getAccountType())) {
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

    /**
     * Generates a single-use 8-character link code that a child can redeem
     * to join this parent's family. Expires after 24 hours. Enforces the
     * per-tier child cap before issuing the code. Any PARENT account can call.
     * Free users capped at 1 child.
     */
    @PostMapping("/generate-link-code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, String>>> generateLinkCode(
            @AuthenticationPrincipal String userId) {
        UserJpaEntity parent = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        if (!"PARENT".equals(parent.getAccountType())) {
            return ResponseEntity.status(403)
                    .body(ApiResponse.error("Only PARENT accounts can generate link codes", 403));
        }
        SubscriptionTier tier = premiumService.resolveTier(userId);
        // Enforce child cap before issuing a new code
        int currentChildren = userRepo.countByParentId(userId);
        int cap = resolveChildCap(tier);
        if (currentChildren >= cap) {
            return ResponseEntity.status(422)
                    .body(ApiResponse.error(
                            "Child cap reached (" + cap + "). Upgrade to add more.", 422));
        }
        // Generate 8-char alphanumeric code (URL-safe subset of UUID)
        String code = java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8).toUpperCase();
        parent.setLinkCode(code);
        parent.setLinkCodeExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        userRepo.save(parent);
        log.info("[Family] Generated link code for parent={}", userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "linkCode",  code,
                "expiresIn", "24 hours"
        )));
    }

    /**
     * Allows a child account to join a family by redeeming a link code issued
     * by the parent. The code is single-use and cleared after claim.
     *
     * <p>Enforces the per-tier maxStudents cap from {@link Entitlements}:
     * <ul>
     *   <li>FREE (SPARK): maxStudents=1 — payer only, no children allowed →
     *       immediately throws UpgradeRequiredException("ADD_STUDENT")</li>
     *   <li>PRO/MAX: maxStudents=1 — same as above</li>
     *   <li>FAMILY: maxStudents=4</li>
     *   <li>CENTRE: maxStudents=15</li>
     * </ul>
     */
    @PostMapping("/join/{linkCode}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> joinFamily(
            @AuthenticationPrincipal String userId,
            @PathVariable String linkCode) {
        UserJpaEntity parent = userRepo.findByLinkCode(linkCode)
                .orElseThrow(() -> new RuntimeException("Invalid or expired link code"));
        if (parent.getLinkCodeExpiresAt() != null
                && Instant.now().isAfter(parent.getLinkCodeExpiresAt())) {
            return ResponseEntity.status(410)
                    .body(ApiResponse.error(
                            "Link code has expired. Ask your parent to generate a new one.", 410));
        }

        // Enforce maxStudents cap for the PAYER (parent / centre owner).
        SubscriptionTier payerTier = premiumService.resolveTier(parent.getId());
        int maxStudents = Entitlements.forTier(payerTier).maxStudents();
        int currentChildren = userRepo.countByParentId(parent.getId());
        if (currentChildren >= maxStudents) {
            throw new UpgradeRequiredException("ADD_STUDENT");
        }

        UserJpaEntity child = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        child.setParentId(parent.getId());
        child.setAccountType("CHILD");
        userRepo.save(child);
        // Evict child's entitlement cache so they immediately inherit premium
        premiumService.evictEntitlement(userId);
        // Clear the code — single-use
        parent.setLinkCode(null);
        parent.setLinkCodeExpiresAt(null);
        userRepo.save(parent);
        log.info("[Family] Child={} joined parent={}", userId, parent.getId());

        // yearLevel >= 7 maps to Sec 1+ (~13+), may require consent
        boolean consentRequired = child.getYearLevel() != null && child.getYearLevel() >= 7;

        Map<String, Object> response = new HashMap<>();
        response.put("message", "Successfully joined family account!");
        response.put("parentId", parent.getId());
        response.put("consentRequired", consentRequired);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

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
