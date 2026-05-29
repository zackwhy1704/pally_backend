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

        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("totalReferred", total);
        body.put("activatedCount", activated);
        body.put("rewardsEarned",
                activated * ReferralService.ACTIVATION_STARS);
        body.put("nextTierAt", nextTier);
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
            @AuthenticationPrincipal String userId) {
        var rows = referralRepo.findByReferrerUserIdOrderByCreatedAtDesc(userId);
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
                "redemptions", dtos)));
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
