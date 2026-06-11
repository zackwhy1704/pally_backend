package com.pally.api.module;

import com.pally.domain.assignment.AssignmentService;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.module.ModuleService;
import com.pally.infrastructure.persistence.assignment.AssignmentCompletionJpaEntity;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Student-facing assignment and exam prep endpoints, scoped to an avatar.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}")
@RequiredArgsConstructor
@Slf4j
public class AssignmentStudentController {

    private final AssignmentService assignmentService;
    private final ModuleService moduleService;
    private final AvatarRepository avatarRepository;

    /**
     * List assignments for this avatar's class, with the student's completion status.
     */
    @GetMapping("/assignments")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAssignments(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        String classId = avatar.getClassId();
        if (classId == null || classId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.success(List.of()));
        }

        List<Map<String, Object>> assignments = assignmentService.listForUser(userId, classId);
        return ResponseEntity.ok(ApiResponse.success(assignments));
    }

    /**
     * Start an assignment — creates/updates the completion record to IN_PROGRESS.
     */
    @PostMapping("/assignments/{assignmentId}/start")
    public ResponseEntity<ApiResponse<Map<String, Object>>> startAssignment(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String assignmentId) {
        AssignmentCompletionJpaEntity completion =
                assignmentService.startAssignment(assignmentId, userId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "assignmentId", assignmentId,
                "status", completion.getStatus(),
                "startedAt", completion.getStartedAt() != null
                        ? completion.getStartedAt().toString() : "")));
    }

    /**
     * Exam prep: aggregates per-concept mastery across all COMPLETE modules,
     * sorted weakest first. Includes test date countdown and daily target.
     */
    @GetMapping("/exam-prep")
    public ResponseEntity<ApiResponse<Map<String, Object>>> examPrep(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        Map<String, Object> prep = moduleService.getExamPrep(avatarId);
        return ResponseEntity.ok(ApiResponse.success(prep));
    }
}
