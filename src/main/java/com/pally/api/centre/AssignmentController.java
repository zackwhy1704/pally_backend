package com.pally.api.centre;

import com.pally.domain.assignment.AssignmentService;
import com.pally.domain.centre.CentreAccessService;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centre-scoped assignment management endpoints.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}/assignments")
@RequiredArgsConstructor
@Slf4j
public class AssignmentController {

    private final CentreAccessService accessService;
    private final AssignmentService assignmentService;
    private final OrgClassJpaRepository classRepo;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAssignment(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        String title = str(body.get("title"));
        String type = str(body.get("type"));
        @SuppressWarnings("unchecked")
        List<String> moduleIds = body.get("moduleIds") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : null;
        @SuppressWarnings("unchecked")
        List<String> itemIds = body.get("itemIds") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : null;
        @SuppressWarnings("unchecked")
        List<String> stages = body.get("stages") instanceof List<?> list
                ? list.stream().map(Object::toString).toList() : null;
        Double masteryThreshold = body.get("masteryThreshold") instanceof Number n
                ? n.doubleValue() : null;
        String dueDateStr = str(body.get("dueDate"));
        Instant dueDate = dueDateStr != null ? Instant.parse(dueDateStr) : null;

        AssignmentJpaEntity assignment = assignmentService.create(
                classId, title, type, moduleIds, itemIds, stages,
                masteryThreshold, dueDate, userId);

        return ResponseEntity.status(201).body(ApiResponse.created(toDto(assignment)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAssignments(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        List<Map<String, Object>> result = assignmentService.listForClass(classId)
                .stream().map(this::toDto).toList();
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/{assignmentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAssignment(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String assignmentId) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        Map<String, Object> detail = assignmentService.getDetail(assignmentId);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteAssignment(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String assignmentId) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);

        assignmentService.delete(assignmentId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true)));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private OrgClassJpaEntity requireClass(String orgId, String classId) {
        OrgClassJpaEntity cls = classRepo.findById(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(cls.getOrganizationId())) {
            throw new BusinessException("Class not in this organization", 403);
        }
        return cls;
    }

    private Map<String, Object> toDto(AssignmentJpaEntity a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("classId", a.getClassId());
        m.put("title", a.getTitle());
        m.put("type", a.getType());
        m.put("moduleIds", a.getModuleIds());
        m.put("itemIds", a.getItemIds());
        m.put("stages", a.getStages());
        m.put("masteryThreshold", a.getMasteryThreshold());
        m.put("dueDate", a.getDueDate().toString());
        m.put("createdBy", a.getCreatedBy());
        m.put("createdAt", a.getCreatedAt().toString());
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
