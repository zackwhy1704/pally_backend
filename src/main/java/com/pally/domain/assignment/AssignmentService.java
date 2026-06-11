package com.pally.domain.assignment;

import com.pally.infrastructure.persistence.assignment.AssignmentCompletionJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentCompletionJpaRepository;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages the assignment lifecycle: creation, listing, starting,
 * completion tracking, and overdue marking.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AssignmentService {

    private static final Set<String> VALID_TYPES = Set.of(
            AssignmentJpaEntity.TYPE_PRE_CLASS,
            AssignmentJpaEntity.TYPE_POST_CLASS,
            AssignmentJpaEntity.TYPE_REVISION,
            AssignmentJpaEntity.TYPE_CUSTOM);

    private final AssignmentJpaRepository assignmentRepo;
    private final AssignmentCompletionJpaRepository completionRepo;
    private final LearningModuleJpaRepository moduleRepo;

    /**
     * Creates an assignment for a class.
     */
    @Transactional
    public AssignmentJpaEntity create(
            String classId, String title, String type,
            List<String> moduleIds, List<String> itemIds,
            List<String> stages, Double masteryThreshold,
            Instant dueDate, String createdBy) {

        if (title == null || title.isBlank()) {
            throw new BusinessException("title is required", 400);
        }
        if (type == null || !VALID_TYPES.contains(type)) {
            throw new BusinessException(
                    "type must be one of: " + VALID_TYPES, 400);
        }
        if (dueDate == null) {
            throw new BusinessException("dueDate is required", 400);
        }

        // For REVISION type, auto-select modules below mastery threshold
        List<String> resolvedModuleIds = moduleIds;
        if (AssignmentJpaEntity.TYPE_REVISION.equals(type)) {
            double threshold = masteryThreshold != null ? masteryThreshold : 60.0;
            resolvedModuleIds = autoSelectRevisionModules(classId, threshold);
            if (resolvedModuleIds.isEmpty()) {
                throw new BusinessException(
                        "No modules below mastery threshold " + threshold + "%", 400);
            }
        }

        // Default stages by type
        List<String> resolvedStages = stages;
        if (resolvedStages == null || resolvedStages.isEmpty()) {
            resolvedStages = defaultStages(type);
        }

        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId(IdGenerator.newId());
        assignment.setClassId(classId);
        assignment.setTitle(title);
        assignment.setType(type);
        assignment.setModuleIds(resolvedModuleIds != null ? String.join(",", resolvedModuleIds) : null);
        assignment.setItemIds(itemIds != null ? String.join(",", itemIds) : null);
        assignment.setStages(resolvedStages != null ? String.join(",", resolvedStages) : null);
        assignment.setMasteryThreshold(
                masteryThreshold != null ? BigDecimal.valueOf(masteryThreshold) : null);
        assignment.setDueDate(dueDate);
        assignment.setCreatedBy(createdBy);
        assignment.setCreatedAt(Instant.now());

        assignmentRepo.save(assignment);
        log.info("[Assignment] Created assignment={} type={} class={}", assignment.getId(), type, classId);
        return assignment;
    }

    /**
     * Lists all assignments for a class, ordered by due date ascending.
     */
    public List<AssignmentJpaEntity> listForClass(String classId) {
        return assignmentRepo.findByClassIdOrderByDueDateAsc(classId);
    }

    /**
     * Returns assignment detail with per-student completion stats.
     */
    public Map<String, Object> getDetail(String assignmentId) {
        AssignmentJpaEntity assignment = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new BusinessException("Assignment not found", 404));

        List<AssignmentCompletionJpaEntity> completions = completionRepo.findByAssignmentId(assignmentId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", assignment.getId());
        result.put("classId", assignment.getClassId());
        result.put("title", assignment.getTitle());
        result.put("type", assignment.getType());
        result.put("moduleIds", assignment.getModuleIds());
        result.put("itemIds", assignment.getItemIds());
        result.put("stages", assignment.getStages());
        result.put("masteryThreshold", assignment.getMasteryThreshold());
        result.put("dueDate", assignment.getDueDate().toString());
        result.put("createdBy", assignment.getCreatedBy());

        int pending = 0;
        int inProgress = 0;
        int completed = 0;
        int overdue = 0;
        List<Map<String, Object>> studentStatuses = new ArrayList<>();

        for (AssignmentCompletionJpaEntity c : completions) {
            switch (c.getStatus()) {
                case AssignmentCompletionJpaEntity.STATUS_PENDING -> pending++;
                case AssignmentCompletionJpaEntity.STATUS_IN_PROGRESS -> inProgress++;
                case AssignmentCompletionJpaEntity.STATUS_COMPLETED -> completed++;
                case AssignmentCompletionJpaEntity.STATUS_OVERDUE -> overdue++;
                default -> { /* unknown status */ }
            }
            Map<String, Object> sc = new LinkedHashMap<>();
            sc.put("userId", c.getUserId());
            sc.put("status", c.getStatus());
            sc.put("startedAt", c.getStartedAt() != null ? c.getStartedAt().toString() : null);
            sc.put("completedAt", c.getCompletedAt() != null ? c.getCompletedAt().toString() : null);
            studentStatuses.add(sc);
        }

        result.put("pendingCount", pending);
        result.put("inProgressCount", inProgress);
        result.put("completedCount", completed);
        result.put("overdueCount", overdue);
        result.put("students", studentStatuses);

        return result;
    }

    /**
     * Starts an assignment for a user: creates or updates the completion record
     * from PENDING to IN_PROGRESS.
     */
    @Transactional
    public AssignmentCompletionJpaEntity startAssignment(String assignmentId, String userId) {
        assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new BusinessException("Assignment not found", 404));

        AssignmentCompletionJpaEntity completion = completionRepo
                .findByAssignmentIdAndUserId(assignmentId, userId)
                .orElseGet(() -> {
                    AssignmentCompletionJpaEntity c = new AssignmentCompletionJpaEntity();
                    c.setId(IdGenerator.newId());
                    c.setAssignmentId(assignmentId);
                    c.setUserId(userId);
                    c.setStatus(AssignmentCompletionJpaEntity.STATUS_PENDING);
                    return c;
                });

        if (AssignmentCompletionJpaEntity.STATUS_COMPLETED.equals(completion.getStatus())) {
            throw new BusinessException("Assignment already completed", 400);
        }

        completion.setStatus(AssignmentCompletionJpaEntity.STATUS_IN_PROGRESS);
        completion.setStartedAt(Instant.now());
        completionRepo.save(completion);

        log.info("[Assignment] Started assignment={} user={}", assignmentId, userId);
        return completion;
    }

    /**
     * Checks if all required modules/stages for an assignment are complete for a user,
     * and marks the assignment as COMPLETED if so.
     * Should be called after a module submit.
     */
    @Transactional
    public void checkAndAdvanceCompletions(String userId) {
        List<AssignmentCompletionJpaEntity> active = completionRepo.findByUserId(userId).stream()
                .filter(c -> AssignmentCompletionJpaEntity.STATUS_IN_PROGRESS.equals(c.getStatus()))
                .toList();

        for (AssignmentCompletionJpaEntity completion : active) {
            AssignmentJpaEntity assignment = assignmentRepo.findById(completion.getAssignmentId())
                    .orElse(null);
            if (assignment == null) continue;

            if (isAssignmentFulfilled(assignment, userId)) {
                completion.setStatus(AssignmentCompletionJpaEntity.STATUS_COMPLETED);
                completion.setCompletedAt(Instant.now());
                completionRepo.save(completion);
                log.info("[Assignment] Completed assignment={} user={}",
                        assignment.getId(), userId);
            }
        }
    }

    /**
     * Marks assignments as OVERDUE where the due date has passed and the
     * completion is not yet COMPLETED.
     */
    @Transactional
    public int markOverdue() {
        List<AssignmentJpaEntity> allAssignments = assignmentRepo.findAll();
        int marked = 0;

        for (AssignmentJpaEntity assignment : allAssignments) {
            if (assignment.getDueDate().isAfter(Instant.now())) continue;

            List<AssignmentCompletionJpaEntity> completions =
                    completionRepo.findByAssignmentId(assignment.getId());

            for (AssignmentCompletionJpaEntity c : completions) {
                if (!AssignmentCompletionJpaEntity.STATUS_COMPLETED.equals(c.getStatus())
                        && !AssignmentCompletionJpaEntity.STATUS_OVERDUE.equals(c.getStatus())) {
                    c.setStatus(AssignmentCompletionJpaEntity.STATUS_OVERDUE);
                    completionRepo.save(c);
                    marked++;
                }
            }
        }

        if (marked > 0) {
            log.info("[Assignment] Marked {} completions as OVERDUE", marked);
        }
        return marked;
    }

    /**
     * Lists assignments for a specific user (via their completions),
     * filtered by class ID from their avatar.
     */
    public List<Map<String, Object>> listForUser(String userId, String classId) {
        List<AssignmentJpaEntity> classAssignments =
                assignmentRepo.findByClassIdOrderByDueDateAsc(classId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (AssignmentJpaEntity a : classAssignments) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("title", a.getTitle());
            m.put("type", a.getType());
            m.put("dueDate", a.getDueDate().toString());
            m.put("stages", a.getStages());

            AssignmentCompletionJpaEntity completion = completionRepo
                    .findByAssignmentIdAndUserId(a.getId(), userId)
                    .orElse(null);
            m.put("status", completion != null ? completion.getStatus()
                    : AssignmentCompletionJpaEntity.STATUS_PENDING);
            m.put("startedAt", completion != null && completion.getStartedAt() != null
                    ? completion.getStartedAt().toString() : null);
            m.put("completedAt", completion != null && completion.getCompletedAt() != null
                    ? completion.getCompletedAt().toString() : null);

            result.add(m);
        }
        return result;
    }

    /**
     * Deletes an assignment and all its completions.
     */
    @Transactional
    public void delete(String assignmentId) {
        if (!assignmentRepo.existsById(assignmentId)) {
            throw new BusinessException("Assignment not found", 404);
        }
        assignmentRepo.deleteById(assignmentId);
        log.info("[Assignment] Deleted assignment={}", assignmentId);
    }

    // ── Private helpers ──────────────────────────────────────────────

    private List<String> defaultStages(String type) {
        return switch (type) {
            case AssignmentJpaEntity.TYPE_PRE_CLASS -> List.of("LEARN", "TEST", "PROVE");
            case AssignmentJpaEntity.TYPE_POST_CLASS -> List.of("TEST", "PROVE");
            case AssignmentJpaEntity.TYPE_REVISION -> List.of("PROVE");
            default -> List.of("LEARN", "TEST", "PROVE");
        };
    }

    private List<String> autoSelectRevisionModules(String classId, double threshold) {
        List<LearningModuleJpaEntity> classModules = moduleRepo.findByClassId(classId);
        return classModules.stream()
                .filter(m -> "COMPLETE".equals(m.getStage()))
                .filter(m -> m.getMasteryPct() != null
                        && m.getMasteryPct().doubleValue() < threshold)
                .map(LearningModuleJpaEntity::getId)
                .collect(Collectors.toList());
    }

    private boolean isAssignmentFulfilled(AssignmentJpaEntity assignment, String userId) {
        String moduleIdsStr = assignment.getModuleIds();
        if (moduleIdsStr == null || moduleIdsStr.isBlank()) return false;

        String stagesStr = assignment.getStages();
        Set<String> requiredStages = (stagesStr != null && !stagesStr.isBlank())
                ? Set.of(stagesStr.split(","))
                : Set.of("LEARN", "TEST", "PROVE");

        String[] moduleIds = moduleIdsStr.split(",");
        for (String moduleId : moduleIds) {
            String trimmed = moduleId.trim();
            if (trimmed.isEmpty()) continue;

            LearningModuleJpaEntity module = moduleRepo.findById(trimmed).orElse(null);
            if (module == null) continue;

            // If PROVE is a required stage and module is not COMPLETE, not fulfilled
            if (requiredStages.contains("PROVE") && !"COMPLETE".equals(module.getStage())) {
                return false;
            }
            // If only TEST is the final required stage
            if (!requiredStages.contains("PROVE") && requiredStages.contains("TEST")) {
                String stage = module.getStage();
                if (!"TEST".equals(stage) && !"PROVE".equals(stage) && !"COMPLETE".equals(stage)) {
                    return false;
                }
            }
        }
        return true;
    }
}
