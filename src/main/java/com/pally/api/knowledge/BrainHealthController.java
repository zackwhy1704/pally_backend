package com.pally.api.knowledge;

import com.pally.domain.knowledge.BrainHealthService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Knowledge-health aggregate per tutor (audit's B-B7). Makes the
 * otherwise-invisible harness state surfaceable to student + parent +
 * production debugging: how many pages are wobbly, how many are flagged
 * for review, how many are archived, what the average certainty trend is.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}/brain-health")
@RequiredArgsConstructor
public class BrainHealthController {

    private final BrainHealthService brainHealthService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> health(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(ApiResponse.success(
                brainHealthService.getHealth(userId, avatarId)));
    }
}
