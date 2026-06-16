package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.ClassBriefService;
import com.pally.domain.centre.ClassCrudService;
import com.pally.domain.centre.ClassMembershipService;
import com.pally.domain.centre.dto.MochiConfig;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP delegator for centre class endpoints.
 * Business logic lives in ClassCrudService, ClassMembershipService, and ClassBriefService.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}")
@RequiredArgsConstructor
@Slf4j
public class ClassController {

    private final ClassCrudService classCrudService;
    private final ClassMembershipService classMembershipService;
    private final ClassBriefService classBriefService;
    private final CentreAccessService accessService;

    // ── Create a class ────────────────────────────────────────────────────────

    @PostMapping("/classes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(ApiResponse.success(classCrudService.createClass(userId, orgId, body)));
    }

    // ── List classes ──────────────────────────────────────────────────────────

    @GetMapping("/classes")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listClasses(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        return ResponseEntity.ok(ApiResponse.success(classCrudService.listClasses(userId, orgId)));
    }

    // ── Edit a class ───────────────────────────────────────────────────────────

    @PatchMapping("/classes/{classId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                ApiResponse.success(classCrudService.updateClass(userId, orgId, classId, body)));
    }

    // ── Delete a class ─────────────────────────────────────────────────────────

    @DeleteMapping("/classes/{classId}")
    public ResponseEntity<Void> deleteClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        classCrudService.deleteClass(userId, orgId, classId);
        return ResponseEntity.noContent().build();
    }

    // ── Mochi config ───────────────────────────────────────────────────────────

    @PatchMapping("/classes/{classId}/mochi-config")
    public ResponseEntity<ApiResponse<MochiConfig>> updateMochiConfig(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody MochiConfig config) {
        return ResponseEntity.ok(
                ApiResponse.success(classCrudService.updateMochiConfig(userId, orgId, classId, config)));
    }

    // ── Teaching style ─────────────────────────────────────────────────────────

    @PatchMapping("/classes/{classId}/teaching-style")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTeachingStyle(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(
                ApiResponse.success(classCrudService.updateTeachingStyle(userId, orgId, classId, body)));
    }

    // ── Centre members ──────────────────────────────────────────────────────────

    @GetMapping("/members")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> members(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        return ResponseEntity.ok(ApiResponse.success(classMembershipService.members(userId, orgId)));
    }

    // ── Assign a member ────────────────────────────────────────────────────────

    @PostMapping("/classes/{classId}/members")
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                ApiResponse.success(classMembershipService.assign(userId, orgId, classId, body)));
    }

    // ── Remove a member ────────────────────────────────────────────────────────

    @DeleteMapping("/classes/{classId}/members/{studentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> remove(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String studentId) {
        return ResponseEntity.ok(
                ApiResponse.success(classMembershipService.remove(userId, orgId, classId, studentId)));
    }

    // ── Class roster ──────────────────────────────────────────────────────────

    @GetMapping("/classes/{classId}/members")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> roster(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(classMembershipService.roster(userId, orgId, classId)));
    }

    // ── Analytics: roster with grasp ──────────────────────────────────────────

    @GetMapping("/classes/{classId}/analytics/roster")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> classRosterAnalytics(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(classMembershipService.classRosterAnalytics(userId, orgId, classId)));
    }

    // ── Analytics: heatmap ────────────────────────────────────────────────────

    @GetMapping("/classes/{classId}/analytics/heatmap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> classHeatmap(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(classMembershipService.classHeatmap(userId, orgId, classId)));
    }

    // ── Backfill CLASS groups (one-time, idempotent) ───────────────────────────

    @PostMapping("/classes/backfill-groups")
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillClassGroups(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        return ResponseEntity.ok(
                ApiResponse.success(classCrudService.backfillClassGroups(userId, orgId)));
    }

    // ── Narration ─────────────────────────────────────────────────────────────

    @PostMapping("/classes/{classId}/modules/{moduleId}/narration/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateClassNarration(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String moduleId,
            @RequestBody(required = false) Map<String, String> body) {
        String narrationId = classCrudService.generateClassNarration(
                userId, orgId, classId, moduleId, body);
        Map<String, Object> response = new LinkedHashMap<>();
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
        return classCrudService.getClassNarration(userId, orgId, classId, moduleId)
                .map(resp -> ResponseEntity.ok(ApiResponse.success(resp)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Narration not found for this module", 404)));
    }

    // ── AI class brief ─────────────────────────────────────────────────────────

    @GetMapping("/classes/{classId}/class-brief")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getClassBrief(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestParam(required = false) String moduleId) {
        accessService.ensureOwner(userId, orgId);
        classCrudService.getClass(orgId, classId);
        return ResponseEntity.ok(
                ApiResponse.success(classBriefService.getOrGenerate(classId, moduleId)));
    }

    @PostMapping("/classes/{classId}/class-brief/refresh")
    public ResponseEntity<ApiResponse<Map<String, Object>>> refreshClassBrief(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestParam(required = false) String moduleId) {
        accessService.ensureOwner(userId, orgId);
        classCrudService.getClass(orgId, classId);
        return ResponseEntity.ok(
                ApiResponse.success(classBriefService.refresh(classId, moduleId)));
    }
}
