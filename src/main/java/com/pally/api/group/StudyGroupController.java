package com.pally.api.group;

import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Entitlement-gated collaborative study groups (PRO+). FREE (SPARK) users get an
 * UPGRADE_REQUIRED 402 on create/join. Thin HTTP layer: delegate to
 * {@link StudyGroupService} → wrap in {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/groups")
@RequiredArgsConstructor
public class StudyGroupController {

    private final StudyGroupService studyGroupService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> listMyGroups(
            @AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.success(studyGroupService.listMyGroups(userId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createGroup(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(studyGroupService.createGroup(userId, body)));
    }

    @PostMapping("/join")
    public ResponseEntity<ApiResponse<Map<String, Object>>> join(
            @AuthenticationPrincipal String userId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(ApiResponse.success(studyGroupService.join(userId, body)));
    }

    @GetMapping("/{groupId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getGroup(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        return ResponseEntity.ok(ApiResponse.success(studyGroupService.getGroup(userId, groupId)));
    }

    @PostMapping("/{groupId}/share")
    public ResponseEntity<ApiResponse<Map<String, Object>>> shareNote(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(studyGroupService.shareNote(userId, groupId, body)));
    }

    @PostMapping("/{groupId}/report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> report(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(studyGroupService.report(userId, groupId, body)));
    }

    @GetMapping("/{groupId}/reports")
    public ResponseEntity<ApiResponse<Map<String, Object>>> listReports(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        return ResponseEntity.ok(
                ApiResponse.success(studyGroupService.listReports(userId, groupId)));
    }

    @DeleteMapping("/{groupId}/members/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> kickMember(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId,
            @PathVariable String targetUserId) {
        studyGroupService.kickMember(userId, groupId, targetUserId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/{groupId}/leave")
    public ResponseEntity<ApiResponse<Void>> leave(
            @AuthenticationPrincipal String userId,
            @PathVariable String groupId) {
        studyGroupService.leave(userId, groupId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
