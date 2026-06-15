package com.pally.api.parent;

import com.pally.api.parent.dto.ParentDashboardResponse;
import com.pally.api.parent.dto.WeeklyReportDetail;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Parent-to-child endpoints. All operations require the authenticated user
 * to be the parent of the addressed child (enforced inside
 * {@link ParentChildService}). Thin HTTP layer: delegate → wrap in
 * {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/parent/children/{childId}")
@RequiredArgsConstructor
public class ParentChildController {

    private final ParentChildService parentChildService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<ParentDashboardResponse>> getDashboard(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.getDashboard(parentId, childId)));
    }

    @GetMapping("/reports")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listReports(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.listReports(parentId, childId)));
    }

    @GetMapping("/reports/{weekId}")
    public ResponseEntity<ApiResponse<WeeklyReportDetail>> getReport(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId,
            @PathVariable String weekId) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.getReport(parentId, childId, weekId)));
    }

    @GetMapping("/modules")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listModules(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.listModules(parentId, childId)));
    }

    @PostMapping("/assign-revision")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assignRevision(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.assignRevision(parentId, childId, body)));
    }

    @PostMapping("/award-stars")
    public ResponseEntity<ApiResponse<Map<String, Object>>> awardStars(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.awardStars(parentId, childId, body)));
    }

    @PutMapping("/goal")
    public ResponseEntity<ApiResponse<Void>> setGoal(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId,
            @RequestBody Map<String, Object> body) {
        parentChildService.setGoal(parentId, childId, body);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @GetMapping("/goal")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGoal(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.getGoal(parentId, childId)));
    }

    @GetMapping("/consent")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getConsent(
            @AuthenticationPrincipal String parentId,
            @PathVariable String childId) {
        return ResponseEntity.ok(
                ApiResponse.success(parentChildService.getConsent(parentId, childId)));
    }
}
