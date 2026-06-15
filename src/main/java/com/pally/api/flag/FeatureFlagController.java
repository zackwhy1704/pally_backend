package com.pally.api.flag;

import com.pally.domain.flag.FeatureFlagService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
 * JWT filter and the {@code hasRole("ADMIN")} gate in {@code SecurityConfig}
 * on {@code /api/v1/admin/**}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
public class FeatureFlagController {

    private final FeatureFlagService featureFlagService;

    @GetMapping("/me/flags")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> myFlags(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(featureFlagService.getFlags(userId)));
    }

    @PostMapping("/admin/users/{targetUserId}/flags/{flagName}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> enableFlag(
            @AuthenticationPrincipal String callerId,
            @PathVariable String targetUserId,
            @PathVariable String flagName) {
        return ResponseEntity.ok(ApiResponse.success(
                featureFlagService.enableFlag(callerId, targetUserId, flagName)));
    }

    @DeleteMapping("/admin/users/{targetUserId}/flags/{flagName}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> disableFlag(
            @AuthenticationPrincipal String callerId,
            @PathVariable String targetUserId,
            @PathVariable String flagName) {
        return ResponseEntity.ok(ApiResponse.success(
                featureFlagService.disableFlag(callerId, targetUserId, flagName)));
    }
}
