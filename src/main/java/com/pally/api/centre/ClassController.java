package com.pally.api.centre;

import com.pally.api.centre.dto.MochiConfig;
import com.pally.domain.centre.CentreAccessService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Centre classes (subject×level). A class owns a join code, a shared corpus,
 * a branded Mochi, and its own roster. Admins create classes and assign centre
 * members into them; each assignment provisions the student's branded, closed-book
 * centre avatar. All endpoints are owner-gated via {@link CentreAccessService}
 * (enforced inside {@link ClassService}).
 *
 * <p>Thin HTTP layer: every method parses the request, delegates to
 * {@link ClassService}, and wraps the result in {@link ApiResponse}. All business
 * logic and repository access live in the service.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}")
@RequiredArgsConstructor
@Slf4j
public class ClassController {

    private final ClassService classService;

    // ── Create a class ────────────────────────────────────────────────────────

    @PostMapping("/classes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(classService.createClass(userId, orgId, body)));
    }

    // ── List classes ──────────────────────────────────────────────────────────

    @GetMapping("/classes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listClasses(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        return ResponseEntity.ok(ApiResponse.success(classService.listClasses(userId, orgId)));
    }

    // ── Edit a class ───────────────────────────────────────────────────────────

    @PatchMapping("/classes/{classId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.updateClass(userId, orgId, classId, body)));
    }

    // ── Delete a class ─────────────────────────────────────────────────────────

    @DeleteMapping("/classes/{classId}")
    public ResponseEntity<Void> deleteClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        classService.deleteClass(userId, orgId, classId);
        return ResponseEntity.noContent().build();
    }

    // ── Set the class Mochi customization config ───────────────────────────────

    @PatchMapping("/classes/{classId}/mochi-config")
    public ResponseEntity<ApiResponse<MochiConfig>> updateMochiConfig(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody MochiConfig config) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.updateMochiConfig(userId, orgId, classId, config)));
    }

    // ── Set the class teaching style ───────────────────────────────────────────

    @PatchMapping("/classes/{classId}/teaching-style")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTeachingStyle(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.updateTeachingStyle(userId, orgId, classId, body)));
    }

    // ── Centre members ──────────────────────────────────────────────────────────

    @GetMapping("/members")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> members(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        return ResponseEntity.ok(ApiResponse.success(classService.members(userId, orgId)));
    }

    // ── Assign a member to a class ─────────────────────────────────────────────

    @PostMapping("/classes/{classId}/members")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.assign(userId, orgId, classId, body)));
    }

    // ── Remove a member ────────────────────────────────────────────────────────

    @DeleteMapping("/classes/{classId}/members/{studentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> remove(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String studentId) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.remove(userId, orgId, classId, studentId)));
    }

    // ── Class roster ──────────────────────────────────────────────────────────

    @GetMapping("/classes/{classId}/members")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> roster(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.roster(userId, orgId, classId)));
    }

    // ── Class analytics: roster with grasp ─────────────────────────────────────

    @GetMapping("/classes/{classId}/analytics/roster")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> classRosterAnalytics(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.classRosterAnalytics(userId, orgId, classId)));
    }

    // ── Class analytics: heatmap ───────────────────────────────────────────────

    @GetMapping("/classes/{classId}/analytics/heatmap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> classHeatmap(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.classHeatmap(userId, orgId, classId)));
    }

    // ── Backfill CLASS groups (one-time, idempotent) ───────────────────────────

    @PostMapping("/classes/backfill-groups")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillClassGroups(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        return ResponseEntity.ok(
                ApiResponse.success(classService.backfillClassGroups(userId, orgId)));
    }

    // ── Centre narration ───────────────────────────────────────────────────────

    @PostMapping("/classes/{classId}/modules/{moduleId}/narration/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateClassNarration(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String moduleId,
            @RequestBody(required = false) Map<String, String> body) {
        String narrationId =
                classService.generateClassNarration(userId, orgId, classId, moduleId, body);
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("narrationId", narrationId);
        response.put("status", "GENERATING");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(response, null, 202));
    }

    @GetMapping("/classes/{classId}/modules/{moduleId}/narration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getClassNarration(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String moduleId) {
        return classService.getClassNarration(userId, orgId, classId, moduleId)
                .map(resp -> ResponseEntity.ok(ApiResponse.success(resp)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Narration not found for this module", 404)));
    }
}
