package com.pally.api.progress;

import com.pally.api.progress.dto.ProgressResponse;
import com.pally.api.progress.dto.ReadingPingRequest;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Progress dashboard endpoints. Thin HTTP layer: each method delegates to
 * {@link ProgressService} and wraps the result in {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/progress")
@RequiredArgsConstructor
public class ProgressController {

    private final ProgressService progressService;

    @GetMapping
    public ResponseEntity<ApiResponse<ProgressResponse>> getProgress(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(progressService.getProgress(userId)));
    }

    @PostMapping("/reading")
    public ResponseEntity<ApiResponse<Map<String, Object>>> logReading(
            @AuthenticationPrincipal String userId,
            @RequestBody ReadingPingRequest request) {
        return ResponseEntity.ok(ApiResponse.success(progressService.logReading(userId, request)));
    }

    @GetMapping("/study-plan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStudyPlan(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(progressService.getStudyPlan(userId)));
    }

    @PostMapping("/study-plan/{taskId}/done")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markStudyPlanTaskDone(
            @AuthenticationPrincipal String userId,
            @PathVariable String taskId) {
        progressService.markStudyPlanTaskDone(userId, taskId);
    }

    @GetMapping("/streak")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStreak(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(progressService.getStreak(userId)));
    }

    @GetMapping("/today")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getToday(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(progressService.getToday(userId)));
    }

    @GetMapping("/level-roadmap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> levelRoadmap(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(progressService.levelRoadmap(userId)));
    }

    @PostMapping("/daily-goal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setDailyGoal(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(progressService.setDailyGoal(userId, body)));
    }

    @GetMapping("/coverage")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCoverage(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(progressService.getCoverage(userId)));
    }
}
