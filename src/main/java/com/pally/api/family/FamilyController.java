package com.pally.api.family;

import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionLimits;
import com.pally.domain.subscription.SubscriptionTier;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Family account endpoints — link children to a parent, list linked children,
 * and enforce per-tier child caps.
 *
 * <p>Requires FAMILY or CENTRE subscription tier. SOLO/PRO/MAX accounts
 * cannot manage family links.
 */
@RestController
@RequestMapping("/api/v1/family")
@RequiredArgsConstructor
@Slf4j
public class FamilyController {

    private final UserJpaRepository userRepo;
    private final PremiumService premiumService;

    /**
     * Returns the list of child accounts linked to the authenticated parent.
     */
    @GetMapping("/children")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listChildren(
            @AuthenticationPrincipal String userId) {
        SubscriptionTier tier = premiumService.resolveTier(userId);
        if (tier != SubscriptionTier.FAMILY && tier != SubscriptionTier.CENTRE) {
            return ResponseEntity.status(403)
                    .body(ApiResponse.error("Family accounts require Family or Centre plan", 403));
        }
        List<UserJpaEntity> children = userRepo.findByParentId(userId);
        List<Map<String, Object>> result = children.stream()
                .map(c -> Map.<String, Object>of(
                        "userId",      c.getId(),
                        "displayName", c.getDisplayName() != null ? c.getDisplayName() : "Child",
                        "email",       c.getEmail(),
                        "accountType", c.getAccountType(),
                        "joinedAt",    c.getCreatedAt() != null ? c.getCreatedAt().toString() : null
                ))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Generates a single-use 8-character link code that a child can redeem
     * to join this parent's family. Expires after 24 hours. Enforces the
     * per-tier child cap before issuing the code.
     */
    @PostMapping("/generate-link-code")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, String>>> generateLinkCode(
            @AuthenticationPrincipal String userId) {
        SubscriptionTier tier = premiumService.resolveTier(userId);
        if (tier != SubscriptionTier.FAMILY && tier != SubscriptionTier.CENTRE) {
            return ResponseEntity.status(403)
                    .body(ApiResponse.error("Requires Family or Centre plan", 403));
        }
        // Enforce child cap before issuing a new code
        int currentChildren = userRepo.countByParentId(userId);
        int cap = SubscriptionLimits.familyChildCap(tier);
        if (currentChildren >= cap) {
            return ResponseEntity.status(422)
                    .body(ApiResponse.error(
                            "Child cap reached (" + cap + "). Upgrade to add more.", 422));
        }
        // Generate 8-char alphanumeric code (URL-safe subset of UUID)
        String code = java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8).toUpperCase();
        UserJpaEntity parent = userRepo.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));
        parent.setAccountType("PARENT");
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
     */
    @PostMapping("/join/{linkCode}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, String>>> joinFamily(
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
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "message",  "Successfully joined family account!",
                "parentId", parent.getId()
        )));
    }
}
