package com.pally.api.centre;

import com.pally.domain.classroom.ClassroomSessionService;
import com.pally.domain.classroom.dto.ClassroomCreateRequest;
import com.pally.domain.classroom.dto.ClassroomStateResponse;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Teacher (centre-gated) endpoints for classroom boss sessions: create from
 * class material, start, end, get live state. Thin delegator to
 * {@link ClassroomSessionService}, which owns all guards/logic.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}/classroom-sessions")
@RequiredArgsConstructor
public class ClassroomSessionAdminController {

    private final ClassroomSessionService classroomSessionService;

    @PostMapping
    public ResponseEntity<ApiResponse<ClassroomStateResponse>> create(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody ClassroomCreateRequest request) {
        return ResponseEntity.status(201).body(ApiResponse.created(
                classroomSessionService.create(userId, orgId, classId, request.wikiPageId())));
    }

    @PostMapping("/{sessionId}/start")
    public ResponseEntity<ApiResponse<ClassroomStateResponse>> start(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(ApiResponse.success(
                classroomSessionService.start(userId, orgId, sessionId)));
    }

    @PostMapping("/{sessionId}/end")
    public ResponseEntity<ApiResponse<ClassroomStateResponse>> end(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(ApiResponse.success(
                classroomSessionService.end(userId, orgId, sessionId)));
    }

    @GetMapping("/{sessionId}/state")
    public ResponseEntity<ApiResponse<ClassroomStateResponse>> state(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String sessionId) {
        return ResponseEntity.ok(ApiResponse.success(
                classroomSessionService.getLiveState(userId, orgId, sessionId)));
    }
}
