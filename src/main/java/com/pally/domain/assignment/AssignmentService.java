package com.pally.domain.assignment;

import com.pally.infrastructure.persistence.assignment.AssignmentCompletionJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentCompletionJpaRepository;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.module.LearningModuleJpaEntity;
import com.pally.infrastructure.persistence.module.LearningModuleJpaRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** Caps how many weak modules a single personalized homework targets. */
    private static final int MAX_WEAK_MODULES = 6;
    /** Fallback mastery threshold (%) when an assignment doesn't specify one. */
    private static final double DEFAULT_THRESHOLD = 60.0;

    private final AssignmentJpaRepository assignmentRepo;
    private final AssignmentCompletionJpaRepository completionRepo;
    private final LearningModuleJpaRepository moduleRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final UserRepository userRepository;

    /**
     * Creates a class-uniform assignment (legacy, non-personalized).
     */
    @Transactional
    public AssignmentJpaEntity create(
            String classId, String title, String type,
            List<String> moduleIds, List<String> itemIds,
            List<String> stages, Double masteryThreshold,
            Instant dueDate, String createdBy) {
        return create(classId, title, type, moduleIds, itemIds, stages,
                masteryThreshold, dueDate, createdBy, false, null, null);
    }

    /**
     * Creates an assignment for a class. When {@code personalized} is true the
     * class-wide module list is NOT the source of truth — each student's targeted
     * set is resolved at start time from their own mastery (see
     * {@link #startAssignment}). {@code topicScope} (comma-separated wiki slugs)
     * bounds that selection; null = whole class. {@code prereqScope} (pre-class
     * only) is the prior-topic slugs to diagnose per student.
     */
    @Transactional
    public AssignmentJpaEntity create(
            String classId, String title, String type,
            List<String> moduleIds, List<String> itemIds,
            List<String> stages, Double masteryThreshold,
            Instant dueDate, String createdBy,
            boolean personalized, String topicScope, String prereqScope) {

        if (title == null || title.isBlank()) {
            throw new BusinessException("title is required", 400);
        }
        if (type == null || !VALID_TYPES.contains(type)) {
            throw new BusinessException(
                    "type must be one of: " + VALID_TYPES, 400);
        }
        // dueDate is OPTIONAL: an assignment with no deadline is valid — it just
        // never goes "overdue". (Previously this 400'd and blocked creation when
        // a teacher left the date blank.)

        // Non-personalized REVISION keeps the legacy class-wide auto-select.
        // A personalized assignment defers module selection to start time, where
        // it runs PER STUDENT against their own mastery (fixes the mixing bug).
        List<String> resolvedModuleIds = moduleIds;
        if (AssignmentJpaEntity.TYPE_REVISION.equals(type) && !personalized) {
            double threshold = masteryThreshold != null ? masteryThreshold : DEFAULT_THRESHOLD;
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
        assignment.setPersonalized(personalized);
        assignment.setTopicScope(topicScope);
        assignment.setPrereqScope(prereqScope);

        assignmentRepo.save(assignment);
        log.info("[Assignment] Created assignment={} type={} class={} personalized={}",
                assignment.getId(), type, classId, personalized);
        return assignment;
    }

    /**
     * Lists all assignments for a class, ordered by due date ascending.
     */
    public List<AssignmentJpaEntity> listForClass(String classId) {
        return assignmentRepo.findByClassIdOrderByDueDateAsc(classId);
    }

    /**
     * Class assignment list with per-assignment completion stats for the teacher
     * dashboard — completed / overdue out of the active student count.
     */
    public List<Map<String, Object>> listForClassWithStats(String classId) {
        int totalStudents = (int) membershipRepo.countByClassIdAndStatusAndRole(
                classId, ClassMembershipJpaEntity.STATUS_ACTIVE, ClassMembershipJpaEntity.ROLE_STUDENT);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AssignmentJpaEntity a : assignmentRepo.findByClassIdOrderByDueDateAsc(classId)) {
            List<AssignmentCompletionJpaEntity> comps = completionRepo.findByAssignmentId(a.getId());
            int completed = (int) comps.stream()
                    .filter(c -> AssignmentCompletionJpaEntity.STATUS_COMPLETED.equals(c.getStatus())).count();
            int overdue = (int) comps.stream()
                    .filter(c -> AssignmentCompletionJpaEntity.STATUS_OVERDUE.equals(c.getStatus())).count();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("classId", a.getClassId());
            m.put("title", a.getTitle());
            m.put("type", a.getType());
            m.put("moduleIds", a.getModuleIds());
            m.put("stages", a.getStages());
            m.put("personalized", a.isPersonalized());
            m.put("dueDate", a.getDueDate().toString());
            m.put("completedCount", completed);
            m.put("overdueCount", overdue);
            m.put("totalStudents", totalStudents);
            m.put("answersReleased", a.answersReleased());
            out.add(m);
        }
        return out;
    }

    /**
     * Returns assignment detail with per-student completion stats.
     */
    public Map<String, Object> getDetail(String assignmentId) {
        AssignmentJpaEntity assignment = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new BusinessException("Assignment not found", 404));

        List<AssignmentCompletionJpaEntity> completions = completionRepo.findByAssignmentId(assignmentId);

        // Batch-resolve student names + the titles of every resolved module, so the
        // teacher sees "Maya: Speed, Percentage · Daniel: Area" instead of raw ids.
        Map<String, String> names = resolveNames(
                completions.stream().map(AssignmentCompletionJpaEntity::getUserId).toList());
        Map<String, String> moduleTitles = resolveModuleTitles(completions);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", assignment.getId());
        result.put("classId", assignment.getClassId());
        result.put("title", assignment.getTitle());
        result.put("type", assignment.getType());
        result.put("moduleIds", assignment.getModuleIds());
        result.put("itemIds", assignment.getItemIds());
        result.put("stages", assignment.getStages());
        result.put("masteryThreshold", assignment.getMasteryThreshold());
        result.put("personalized", assignment.isPersonalized());
        result.put("topicScope", assignment.getTopicScope());
        result.put("prereqScope", assignment.getPrereqScope());
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
            sc.put("displayName", names.getOrDefault(c.getUserId(), ""));
            sc.put("status", c.getStatus());
            sc.put("startedAt", c.getStartedAt() != null ? c.getStartedAt().toString() : null);
            sc.put("completedAt", c.getCompletedAt() != null ? c.getCompletedAt().toString() : null);
            // Per-student resolved targets — the teacher sees who's getting what
            // ("Maya: Speed, Percentage · Daniel: Area"). Empty until the student starts.
            sc.put("resolvedModuleIds", c.getResolvedModuleIds());
            sc.put("resolvedModules", namedModules(c.getResolvedModuleIds(), moduleTitles));
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
        AssignmentJpaEntity assignment = assignmentRepo.findById(assignmentId)
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

        // Personalized assignments snapshot the student's targeted module set on
        // first start (stable thereafter; reflects their mastery at that moment).
        if (assignment.isPersonalized() && completion.getResolvedModuleIds() == null) {
            resolvePersonalized(assignment, userId, completion);
            // A post-class student who has already mastered everything in scope is
            // auto-completed inside resolvePersonalized — no busywork to start.
            if (AssignmentCompletionJpaEntity.STATUS_COMPLETED.equals(completion.getStatus())) {
                log.info("[Assignment] Mastered (no remediation) assignment={} user={}", assignmentId, userId);
                return completion;
            }
        }

        completion.setStatus(AssignmentCompletionJpaEntity.STATUS_IN_PROGRESS);
        completion.setStartedAt(Instant.now());
        completionRepo.save(completion);

        log.info("[Assignment] Started assignment={} user={}", assignmentId, userId);
        return completion;
    }

    /**
     * Resolves and snapshots the per-student targeted module set on the completion.
     * POST_CLASS/REVISION → the student's own weak modules in scope; PRE_CLASS/
     * CUSTOM → primer-uniform (class-wide) for now (adaptive diagnostic is a later
     * phase). If the student's class avatar can't be resolved, falls back to the
     * class-wide set rather than mis-grading them.
     */
    private void resolvePersonalized(AssignmentJpaEntity a, String userId,
                                     AssignmentCompletionJpaEntity completion) {
        String avatarId = resolveStudentAvatarId(a.getClassId(), userId);
        double threshold = a.getMasteryThreshold() != null
                ? a.getMasteryThreshold().doubleValue() : DEFAULT_THRESHOLD;
        List<String> scope = parseCsv(a.getTopicScope());

        if (avatarId == null) {
            // No class-avatar link — can't personalize. Fall back to class-wide so
            // the student is graded against something real, never auto-mastered.
            completion.setResolvedModuleIds(a.getModuleIds() != null ? a.getModuleIds() : "");
            completion.setResolvedAt(Instant.now());
            log.warn("[Assignment] Could not resolve avatar for personalized assignment={} user={} "
                    + "— using class-wide set", a.getId(), userId);
            return;
        }

        List<String> resolved = switch (a.getType()) {
            case AssignmentJpaEntity.TYPE_POST_CLASS, AssignmentJpaEntity.TYPE_REVISION ->
                    selectWeakModulesForStudent(a.getClassId(), avatarId, threshold, scope);
            case AssignmentJpaEntity.TYPE_PRE_CLASS -> {
                // Pre-class: uniform primer (the NEW topic — no mastery exists yet)
                // PLUS a per-student diagnostic over the teacher's selected prior
                // topics, weighted to the prerequisites THIS student is weakest on.
                List<String> primer = parseCsv(a.getModuleIds());
                List<String> prereqSlugs = parseCsv(a.getPrereqScope());
                List<String> diagnostic = prereqSlugs.isEmpty()
                        ? List.of()
                        : selectWeakModulesForStudent(a.getClassId(), avatarId, threshold, prereqSlugs);
                List<String> merged = new ArrayList<>(primer);
                for (String d : diagnostic) {
                    if (!merged.contains(d)) merged.add(d);
                }
                yield merged;
            }
            // CUSTOM personalized: class-wide.
            default -> parseCsv(a.getModuleIds());
        };

        completion.setResolvedModuleIds(String.join(",", resolved));
        completion.setResolvedAt(Instant.now());

        // Phase 3 honesty rule: a post-class student with NO weak modules in scope
        // has mastered the topic. Don't invent filler — auto-complete with a marker.
        if (AssignmentJpaEntity.TYPE_POST_CLASS.equals(a.getType()) && resolved.isEmpty()) {
            completion.setStatus(AssignmentCompletionJpaEntity.STATUS_COMPLETED);
            completion.setStartedAt(Instant.now());
            completion.setCompletedAt(Instant.now());
            completion.setScoreSummaryJson("{\"mastered\":true,\"reason\":\"no remediation needed\"}");
            completionRepo.save(completion);
        }
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
            m.put("personalized", a.isPersonalized());

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
     * Sets or edits the teacher's model answer(s) for an assignment. Does not
     * change release state. Returns the saved assignment.
     */
    @Transactional
    public AssignmentJpaEntity setModelAnswer(String assignmentId, String modelAnswer) {
        AssignmentJpaEntity a = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new BusinessException("Assignment not found", 404));
        a.setModelAnswer(modelAnswer);
        assignmentRepo.save(a);
        log.info("[Assignment] model answer set assignment={}", assignmentId);
        return a;
    }

    /**
     * Schedules (or immediately performs) the model-answer release.
     *
     * @param releaseAt when answers become visible. Null defaults to the
     *                  assignment's due date. A value <= now releases immediately.
     * @return the saved assignment.
     */
    @Transactional
    public AssignmentJpaEntity releaseAnswers(String assignmentId, Instant releaseAt) {
        AssignmentJpaEntity a = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new BusinessException("Assignment not found", 404));
        Instant when = releaseAt != null ? releaseAt : a.getDueDate();
        a.setAnswersReleasedAt(when);
        assignmentRepo.save(a);
        log.info("[Assignment] answers release scheduled assignment={} at={}", assignmentId, when);
        return a;
    }

    /**
     * Builds the student-facing assignment detail. CRITICAL: the model answer is
     * included ONLY when answers are released ({@code answersReleasedAt <= now}).
     * Before release the {@code modelAnswer} key is absent entirely.
     */
    public Map<String, Object> getStudentDetail(String assignmentId, String userId) {
        AssignmentJpaEntity a = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new BusinessException("Assignment not found", 404));

        AssignmentCompletionJpaEntity completion = completionRepo
                .findByAssignmentIdAndUserId(assignmentId, userId).orElse(null);

        // Personalized assignments expose the STUDENT's resolved module set (once
        // started); otherwise the class-wide list. The student app reads this key
        // transparently, so per-student targeting "just works".
        String moduleIds = a.getModuleIds();
        if (a.isPersonalized() && completion != null && completion.getResolvedModuleIds() != null) {
            moduleIds = completion.getResolvedModuleIds();
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("classId", a.getClassId());
        m.put("title", a.getTitle());
        m.put("type", a.getType());
        m.put("moduleIds", moduleIds);
        m.put("personalized", a.isPersonalized());
        m.put("stages", a.getStages());
        m.put("dueDate", a.getDueDate().toString());
        m.put("answersReleased", a.answersReleased());
        m.put("answersReleasedAt",
                a.getAnswersReleasedAt() != null ? a.getAnswersReleasedAt().toString() : null);

        m.put("status", completion != null ? completion.getStatus()
                : AssignmentCompletionJpaEntity.STATUS_PENDING);

        // SERVER-WITHHELD: only expose model answers post-release. The key is
        // absent (not null) before release so no client can render anything.
        if (a.answersReleased()) {
            m.put("modelAnswer", a.getModelAnswer());
        }
        return m;
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

    /**
     * Pre-class readiness map (Phase 2 payoff). For each enrolled student, returns
     * their mastery snapshot on the assignment's teacher-selected prerequisite
     * slugs and which they are weak on — so the teacher can tailor the lesson to
     * the gaps the class walked in with. Reuses the per-student module mastery data.
     */
    public Map<String, Object> getReadiness(String assignmentId) {
        AssignmentJpaEntity a = assignmentRepo.findById(assignmentId)
                .orElseThrow(() -> new BusinessException("Assignment not found", 404));
        double threshold = a.getMasteryThreshold() != null
                ? a.getMasteryThreshold().doubleValue() : DEFAULT_THRESHOLD;
        Set<String> scope = new HashSet<>(parseCsv(a.getPrereqScope()));

        List<ClassMembershipJpaEntity> members = membershipRepo.findByClassId(a.getClassId());
        Map<String, String> names = resolveNames(
                members.stream().map(ClassMembershipJpaEntity::getUserId).toList());

        List<Map<String, Object>> students = new ArrayList<>();
        for (ClassMembershipJpaEntity member : members) {
            String avatarId = member.getStudentAvatarId();
            if (avatarId == null || avatarId.isBlank()) continue;

            List<Map<String, Object>> concepts = new ArrayList<>();
            int weakCount = 0;
            for (LearningModuleJpaEntity m : moduleRepo.findByClassIdAndAvatarId(a.getClassId(), avatarId)) {
                if (!scope.isEmpty() && !scope.contains(m.getWikiPageSlug())) continue;
                boolean attempted = "COMPLETE".equals(m.getStage());
                Double mastery = m.getMasteryPct() != null ? m.getMasteryPct().doubleValue() : null;
                boolean weak = attempted && mastery != null && mastery < threshold;
                if (weak) weakCount++;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("slug", m.getWikiPageSlug());
                c.put("title", m.getTitle());
                c.put("masteryPct", mastery);
                c.put("attempted", attempted);
                c.put("weak", weak);
                concepts.add(c);
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("userId", member.getUserId());
            s.put("displayName", names.getOrDefault(member.getUserId(), ""));
            s.put("avatarId", avatarId);
            s.put("weakCount", weakCount);
            s.put("concepts", concepts);
            students.add(s);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("assignmentId", a.getId());
        result.put("classId", a.getClassId());
        result.put("type", a.getType());
        result.put("prereqScope", a.getPrereqScope());
        result.put("masteryThreshold", threshold);
        result.put("students", students);
        return result;
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

    /**
     * @deprecated Mixes ALL students' modules for a class (per-row modules are
     *     per-student). Retained only for legacy non-personalized REVISION create.
     *     Personalized assignments use {@link #selectWeakModulesForStudent}.
     */
    @Deprecated
    private List<String> autoSelectRevisionModules(String classId, double threshold) {
        List<LearningModuleJpaEntity> classModules = moduleRepo.findByClassId(classId);
        return classModules.stream()
                .filter(m -> "COMPLETE".equals(m.getStage()))
                .filter(m -> m.getMasteryPct() != null
                        && m.getMasteryPct().doubleValue() < threshold)
                .map(LearningModuleJpaEntity::getId)
                .collect(Collectors.toList());
    }

    /**
     * Selects a single student's weak modules for a class — the heart of
     * per-student differentiation. Keeps the student's OWN attempted ({@code
     * COMPLETE}) modules below {@code threshold}, optionally restricted to
     * {@code topicScopeSlugs}, weakest-first, capped at {@link #MAX_WEAK_MODULES}.
     */
    public List<String> selectWeakModulesForStudent(
            String classId, String avatarId, double threshold, List<String> topicScopeSlugs) {
        if (avatarId == null || avatarId.isBlank()) return List.of();
        Set<String> scope = topicScopeSlugs == null ? Set.of()
                : topicScopeSlugs.stream().map(String::trim)
                        .filter(s -> !s.isEmpty()).collect(Collectors.toSet());

        return moduleRepo.findByClassIdAndAvatarId(classId, avatarId).stream()
                .filter(m -> "COMPLETE".equals(m.getStage()))
                .filter(m -> m.getMasteryPct() != null
                        && m.getMasteryPct().doubleValue() < threshold)
                .filter(m -> scope.isEmpty() || scope.contains(m.getWikiPageSlug()))
                .sorted(Comparator.comparing(LearningModuleJpaEntity::getMasteryPct))
                .limit(MAX_WEAK_MODULES)
                .map(LearningModuleJpaEntity::getId)
                .collect(Collectors.toList());
    }

    /** Resolves a student's class avatar from (classId, userId) via membership. */
    private String resolveStudentAvatarId(String classId, String userId) {
        if (classId == null) return null;
        return membershipRepo.findByClassIdAndUserId(classId, userId)
                .map(ClassMembershipJpaEntity::getStudentAvatarId)
                .filter(a -> a != null && !a.isBlank())
                .orElse(null);
    }

    /** Splits a comma-separated string into a trimmed, non-empty list. */
    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** Batch userId → display name (blank when unknown). */
    private Map<String, String> resolveNames(Collection<String> userIds) {
        Set<String> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) return Map.of();
        Map<String, String> out = new HashMap<>();
        for (User u : userRepository.findAllByIds(ids)) {
            out.put(u.getId(), u.getDisplayName() != null ? u.getDisplayName() : "");
        }
        return out;
    }

    /** Batch resolved-module-id → title across all completions in one fetch. */
    private Map<String, String> resolveModuleTitles(List<AssignmentCompletionJpaEntity> completions) {
        Set<String> moduleIds = completions.stream()
                .map(AssignmentCompletionJpaEntity::getResolvedModuleIds)
                .filter(s -> s != null && !s.isBlank())
                .flatMap(s -> parseCsv(s).stream())
                .collect(Collectors.toSet());
        if (moduleIds.isEmpty()) return Map.of();
        Map<String, String> titles = new HashMap<>();
        for (LearningModuleJpaEntity m : moduleRepo.findAllById(moduleIds)) {
            titles.put(m.getId(), m.getTitle());
        }
        return titles;
    }

    /** A resolved-module CSV → list of {id, title} for the teacher view. */
    private List<Map<String, String>> namedModules(String csv, Map<String, String> titles) {
        List<Map<String, String>> out = new ArrayList<>();
        for (String id : parseCsv(csv)) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("title", titles.getOrDefault(id, id));
            out.add(m);
        }
        return out;
    }

    private boolean isAssignmentFulfilled(AssignmentJpaEntity assignment, String userId) {
        // Personalized: grade against the STUDENT's resolved set, not the class-wide
        // list — otherwise a differentiated student is checked against wrong modules.
        String moduleIdsStr;
        if (assignment.isPersonalized()) {
            moduleIdsStr = completionRepo.findByAssignmentIdAndUserId(assignment.getId(), userId)
                    .map(AssignmentCompletionJpaEntity::getResolvedModuleIds)
                    .filter(s -> s != null)
                    .orElse(assignment.getModuleIds());
        } else {
            moduleIdsStr = assignment.getModuleIds();
        }
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
