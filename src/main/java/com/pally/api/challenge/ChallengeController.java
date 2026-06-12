package com.pally.api.challenge;

import com.pally.domain.challenge.ChallengeService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Student-facing class challenge endpoints. The teacher CREATE endpoint lives on
 * the centre-scoped {@link com.pally.api.centre.ChallengeAdminController}; these
 * routes are for answering, viewing (reveal-gated), and listing.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ChallengeController {

    private final ChallengeService challengeService;

    /**
     * Lock in the caller's answer. 409 if already answered (answers are immutable).
     * Correctness is NOT returned before reveal.
     */
    @PostMapping("/api/v1/challenges/{id}/answer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> answer(
            @AuthenticationPrincipal String userId,
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        String answer = body != null ? body.get("answer") : null;
        Map<String, Object> result = challengeService.answer(id, userId, answer);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Get a challenge. Before reveal: question + options + whether the caller
     * answered. After reveal: adds the correct answer + answer distribution.
     */
    @GetMapping("/api/v1/challenges/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> get(
            @AuthenticationPrincipal String userId,
            @PathVariable String id) {
        Map<String, Object> result = challengeService.get(id, userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /** List all challenges for a class feed (newest first). */
    @GetMapping("/api/v1/classes/{classId}/challenges")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listForClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String classId) {
        List<Map<String, Object>> result = challengeService.listForClass(classId, userId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
