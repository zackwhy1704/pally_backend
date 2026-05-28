package com.pally.api.flag;

import com.pally.infrastructure.persistence.flag.UserFeatureFlagJpaEntity;
import com.pally.infrastructure.persistence.flag.UserFeatureFlagJpaRepository;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Feature flags per user. Drives pilot rollouts (e.g. study groups) without
 * client redeploys.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET  /api/v1/me/flags} — every enabled flag for the current user</li>
 *   <li>{@code POST /api/v1/admin/users/{userId}/flags/{flagName}} — enable</li>
 *   <li>{@code DELETE /api/v1/admin/users/{userId}/flags/{flagName}} — disable</li>
 * </ul>
 *
 * <p>Defaults to off (no row = disabled). Admin endpoints are protected by the
 * same JWT filter as the rest of the API; in production they should also be
 * gated behind a role check (TODO).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagController {

    private final UserFeatureFlagJpaRepository flagRepo;

    @GetMapping("/me/flags")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> myFlags(
            @AuthenticationPrincipal String userId) {
        Map<String, Boolean> flags = new HashMap<>();
        for (var row : flagRepo.findByUserId(userId)) {
            flags.put(row.getFlagName(), row.isEnabled());
        }
        return ResponseEntity.ok(ApiResponse.success(flags));
    }

    @PostMapping("/admin/users/{targetUserId}/flags/{flagName}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> enableFlag(
            @AuthenticationPrincipal String callerId,
            @PathVariable String targetUserId,
            @PathVariable String flagName) {
        UserFeatureFlagJpaEntity entity = flagRepo
                .findByUserIdAndFlagName(targetUserId, flagName)
                .orElseGet(UserFeatureFlagJpaEntity::new);
        entity.setUserId(targetUserId);
        entity.setFlagName(flagName);
        entity.setEnabled(true);
        entity.setUpdatedAt(Instant.now());
        flagRepo.save(entity);
        log.info("[Flags] caller={} enabled flag={} for user={}",
                callerId, flagName, targetUserId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "userId", targetUserId,
                "flagName", flagName,
                "enabled", true)));
    }

    @DeleteMapping("/admin/users/{targetUserId}/flags/{flagName}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> disableFlag(
            @AuthenticationPrincipal String callerId,
            @PathVariable String targetUserId,
            @PathVariable String flagName) {
        flagRepo.deleteByUserIdAndFlagName(targetUserId, flagName);
        log.info("[Flags] caller={} disabled flag={} for user={}",
                callerId, flagName, targetUserId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "userId", targetUserId,
                "flagName", flagName,
                "enabled", false)));
    }
}
