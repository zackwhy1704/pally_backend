package com.pally.api.centre;

import com.pally.domain.assignment.AssignmentService;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.group.ClassGroupService;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaEntity;
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
import org.springframework.web.bind.annotation.PutMapping;
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
    private final ClassGroupService classGroupService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAssignment(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
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

        // Per-student differentiation: when on, each student's targeted module set
        // is resolved at start. topicScope (wiki slugs) accepts a List or CSV string.
        boolean personalized = Boolean.TRUE.equals(body.get("personalized"));
        String topicScope = topicScopeCsv(body.get("topicScope"));
        String prereqScope = topicScopeCsv(body.get("prereqScope"));

        AssignmentJpaEntity assignment = assignmentService.create(
                classId, title, type, moduleIds, itemIds, stages,
                masteryThreshold, dueDate, userId, personalized, topicScope, prereqScope);

        return ResponseEntity.status(201).body(ApiResponse.created(toDto(assignment)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listAssignments(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureStaff(userId, orgId);
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
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        Map<String, Object> detail = assignmentService.getDetail(assignmentId);
        return ResponseEntity.ok(ApiResponse.success(detail));
    }

    /**
     * Pre-class readiness (Phase 2): per-student weak-concept map over the
     * assignment's prerequisite topics, so the teacher can tailor the lesson to
     * the gaps the class walked in with. Staff-gated.
     */
    @GetMapping("/{assignmentId}/readiness")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReadiness(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String assignmentId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        return ResponseEntity.ok(ApiResponse.success(
                assignmentService.getReadiness(assignmentId)));
    }

    @DeleteMapping("/{assignmentId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteAssignment(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String assignmentId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        assignmentService.delete(assignmentId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("deleted", true)));
    }

    // ── Model answers: set / edit (teacher, centre-gated) ────────────────

    @PutMapping("/{assignmentId}/model-answer")
    public ResponseEntity<ApiResponse<Map<String, Object>>> setModelAnswer(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String assignmentId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        // Accept a structured JSON object OR a plain string. Serialize objects.
        Object modelAnswerRaw = body.get("modelAnswer");
        String modelAnswer = serializeModelAnswer(modelAnswerRaw);
        AssignmentJpaEntity a = assignmentService.setModelAnswer(assignmentId, modelAnswer);
        return ResponseEntity.ok(ApiResponse.success(toDto(a)));
    }

    // ── Release model answers (teacher) ──────────────────────────────────
    // "release now": pass {"releaseNow": true}. Schedule: {"releaseAt": "<iso>"}.
    // Omit both to default to the assignment deadline.

    @PostMapping("/{assignmentId}/release")
    public ResponseEntity<ApiResponse<Map<String, Object>>> releaseAnswers(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String assignmentId,
            @RequestBody(required = false) Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        Instant releaseAt = null;
        boolean releaseNow = body != null && Boolean.TRUE.equals(body.get("releaseNow"));
        if (releaseNow) {
            releaseAt = Instant.now();
        } else if (body != null && body.get("releaseAt") != null) {
            releaseAt = Instant.parse(str(body.get("releaseAt")));
        }
        AssignmentJpaEntity a = assignmentService.releaseAnswers(assignmentId, releaseAt);

        // On release (now or already-past schedule), announce in the CLASS group.
        if (a.answersReleased()) {
            classGroupService.postSystemMessage(
                    classId,
                    GroupSystemPostJpaEntity.KIND_ANSWERS_RELEASED,
                    "Answers for “" + a.getTitle() + "” are out ✅",
                    a.getId());
        }
        return ResponseEntity.ok(ApiResponse.success(toDto(a)));
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String serializeModelAnswer(Object raw) {
        if (raw == null) return null;
        if (raw instanceof String s) return s;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
        } catch (Exception e) {
            return raw.toString();
        }
    }

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
        m.put("personalized", a.isPersonalized());
        m.put("topicScope", a.getTopicScope());
        m.put("prereqScope", a.getPrereqScope());
        m.put("dueDate", a.getDueDate().toString());
        m.put("createdBy", a.getCreatedBy());
        m.put("createdAt", a.getCreatedAt().toString());
        // Teacher view: model answer is always visible to the owner.
        m.put("modelAnswer", a.getModelAnswer());
        m.put("answersReleased", a.answersReleased());
        m.put("answersReleasedAt",
                a.getAnswersReleasedAt() != null ? a.getAnswersReleasedAt().toString() : null);
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** Accepts topicScope as a JSON array of slugs OR a CSV string; stores CSV. */
    private static String topicScopeCsv(Object raw) {
        if (raw == null) return null;
        if (raw instanceof List<?> list) {
            String csv = list.stream().map(Object::toString)
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .collect(java.util.stream.Collectors.joining(","));
            return csv.isEmpty() ? null : csv;
        }
        String s = raw.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
