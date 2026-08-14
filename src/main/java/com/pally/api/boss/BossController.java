package com.pally.api.boss;

import com.pally.domain.boss.BossBattleService;
import com.pally.domain.boss.dto.BossAttackRequest;
import com.pally.domain.boss.dto.BossAttackResponse;
import com.pally.domain.boss.dto.BossStateResponse;
import com.pally.domain.consent.ConsentGuard;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Boss battle endpoints (Phase 1, v1), scoped under {@code /api/v1/avatars/{avatarId}}.
 * Thin HTTP layer: delegate to {@link BossBattleService} → wrap in {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/boss")
@RequiredArgsConstructor
public class BossController {

    private final BossBattleService bossBattleService;
    private final ConsentGuard consentGuard;

    /// Detect-or-get chokepoint: returns the active boss, or spawns one from
    /// the student's weakest topic. May trigger AI question generation, so it's
    /// gated the same as /quiz/daily.
    @GetMapping("/active")
    public ResponseEntity<ApiResponse<BossStateResponse>> getActive(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        consentGuard.requireAiAllowed(userId);
        return ResponseEntity.ok(
                ApiResponse.success(bossBattleService.getActiveOrDetect(userId, avatarId)));
    }

    @PostMapping("/{bossId}/attack")
    public ResponseEntity<ApiResponse<BossAttackResponse>> attack(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String bossId,
            @RequestBody BossAttackRequest request) {
        return ResponseEntity.ok(ApiResponse.success(bossBattleService.attack(
                bossId, userId, avatarId, request.questionId(), request.selectedIndex())));
    }
}
