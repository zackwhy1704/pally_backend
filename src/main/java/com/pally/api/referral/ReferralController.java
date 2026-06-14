package com.pally.api.referral;

import com.pally.domain.referral.ReferralService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.referral.ReferralJpaEntity;
import com.pally.infrastructure.persistence.referral.ReferralJpaRepository;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/referral")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;
    private final ReferralJpaRepository referralRepo;
    private final UserJpaRepository userRepo;

    @GetMapping("/me")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> me(
            @AuthenticationPrincipal String userId) {
        String code = referralService.ensureCodeFor(userId);
        long total = referralRepo.countByReferrerUserId(userId);
        long activated = referralRepo.countByReferrerUserIdAndStatus(
                userId, ReferralJpaEntity.STATUS_ACTIVATED);
        int nextTier = referralService.nextTier(activated);

        // Server-truth milestone ladder so the client tier UI is never cosmetic:
        // each entry is the activated-friends threshold + the real star bonus paid.
        java.util.List<Map<String, Object>> milestones = new java.util.ArrayList<>();
        for (int tier : ReferralService.TIERS) {
            Map<String, Object> ms = new HashMap<>();
            ms.put("friends", tier);
            ms.put("starBonus", ReferralService.starBonusForTier(tier));
            ms.put("reached", activated >= tier);
            milestones.add(ms);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("totalReferred", total);
        body.put("activatedCount", activated);
        // Per-activation stars + every milestone bonus already crossed.
        long milestonesEarned = 0;
        for (int tier : ReferralService.TIERS) {
            if (activated >= tier) milestonesEarned += ReferralService.starBonusForTier(tier);
        }
        body.put("rewardsEarned",
                activated * ReferralService.ACTIVATION_STARS + milestonesEarned);
        body.put("nextTierAt", nextTier);
        body.put("nextTierBonus", ReferralService.starBonusForTier(nextTier));
        body.put("milestones", milestones);
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    @PostMapping("/redeem")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> redeem(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        String code = body == null ? null : body.get("code");
        referralService.redeem(userId, code);
        return ResponseEntity.ok(ApiResponse.success(Map.of("status", "pending")));
    }

    @GetMapping("/redemptions")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> redemptions(
            @AuthenticationPrincipal String userId,
            @org.springframework.web.bind.annotation.RequestParam(
                    defaultValue = "0") int page,
            @org.springframework.web.bind.annotation.RequestParam(
                    defaultValue = "50") int size) {
        int safeSize = Math.max(1, Math.min(size, 200));
        var pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page), safeSize);
        var rows = referralRepo.findByReferrerUserIdOrderByCreatedAtDesc(
                userId, pageable);
        List<Map<String, Object>> dtos = new ArrayList<>();
        for (var r : rows) {
            UserJpaEntity referee = userRepo.findById(r.getRefereeUserId())
                    .orElse(null);
            dtos.add(Map.of(
                    "displayName", maskName(referee),
                    "status", r.getStatus(),
                    "joinedAt", r.getCreatedAt().toString(),
                    "activatedAt", r.getActivatedAt() == null
                            ? "" : r.getActivatedAt().toString()));
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "redemptions", dtos,
                "page", rows.getNumber(),
                "size", rows.getSize(),
                "totalElements", rows.getTotalElements(),
                "totalPages", rows.getTotalPages())));
    }

    /// Privacy: never expose email or the full name; render a first-name
    /// (or first initial fallback) so the parent's friends list stays
    /// child-safe without leaking PII.
    private String maskName(UserJpaEntity u) {
        if (u == null) return "Friend";
        String name = u.getChildName() != null && !u.getChildName().isBlank()
                ? u.getChildName()
                : u.getDisplayName();
        if (name == null || name.isBlank()) return "Friend";
        String first = name.trim().split("\\s+")[0];
        return first.length() > 12 ? first.substring(0, 12) : first;
    }
}
