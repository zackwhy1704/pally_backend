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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AssignmentServiceTest {

    @Mock private AssignmentJpaRepository assignmentRepo;
    @Mock private AssignmentCompletionJpaRepository completionRepo;
    @Mock private LearningModuleJpaRepository moduleRepo;
    @Mock private ClassMembershipJpaRepository membershipRepo;
    @Mock private UserRepository userRepository;

    private AssignmentService service;

    @BeforeEach
    void setUp() {
        service = new AssignmentService(assignmentRepo, completionRepo, moduleRepo,
                membershipRepo, userRepository);
    }

    // ── create ──────────────────────────────────────────────────────

    @Test
    void create_preClass_setsDefaultStages() {
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentJpaEntity result = service.create(
                "class-1", "Homework 1", "PRE_CLASS",
                List.of("mod-1"), null, null, null,
                Instant.now().plus(7, ChronoUnit.DAYS), "teacher-1");

        assertThat(result.getType()).isEqualTo("PRE_CLASS");
        assertThat(result.getStages()).isEqualTo("LEARN,TEST,PROVE");
        assertThat(result.getClassId()).isEqualTo("class-1");
        verify(assignmentRepo).save(any());
    }

    @Test
    void create_postClass_setsDefaultStages() {
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentJpaEntity result = service.create(
                "class-1", "Review", "POST_CLASS",
                List.of("mod-1"), null, null, null,
                Instant.now().plus(3, ChronoUnit.DAYS), "teacher-1");

        assertThat(result.getStages()).isEqualTo("TEST,PROVE");
    }

    @Test
    void create_revision_autoSelectsModulesBelowThreshold() {
        LearningModuleJpaEntity mod1 = buildModule("mod-1", "COMPLETE", 45.0);
        LearningModuleJpaEntity mod2 = buildModule("mod-2", "COMPLETE", 80.0);
        when(moduleRepo.findByClassId("class-1")).thenReturn(List.of(mod1, mod2));
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentJpaEntity result = service.create(
                "class-1", "Revision", "REVISION",
                null, null, null, 60.0,
                Instant.now().plus(5, ChronoUnit.DAYS), "teacher-1");

        assertThat(result.getModuleIds()).isEqualTo("mod-1");
        assertThat(result.getStages()).isEqualTo("PROVE");
    }

    @Test
    void create_revision_noModulesBelow_throws400() {
        LearningModuleJpaEntity mod1 = buildModule("mod-1", "COMPLETE", 80.0);
        when(moduleRepo.findByClassId("class-1")).thenReturn(List.of(mod1));

        assertThatThrownBy(() -> service.create(
                "class-1", "Revision", "REVISION",
                null, null, null, 60.0,
                Instant.now().plus(5, ChronoUnit.DAYS), "teacher-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No modules below mastery threshold");
    }

    @Test
    void create_customType_usesExplicitItemIds() {
        when(assignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentJpaEntity result = service.create(
                "class-1", "Custom", "CUSTOM",
                null, List.of("item-1", "item-2"), List.of("TEST"), null,
                Instant.now().plus(7, ChronoUnit.DAYS), "teacher-1");

        assertThat(result.getItemIds()).isEqualTo("item-1,item-2");
        assertThat(result.getStages()).isEqualTo("TEST");
    }

    @Test
    void create_missingTitle_throws400() {
        assertThatThrownBy(() -> service.create(
                "class-1", "", "PRE_CLASS",
                null, null, null, null,
                Instant.now(), "teacher-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("title is required");
    }

    @Test
    void create_invalidType_throws400() {
        assertThatThrownBy(() -> service.create(
                "class-1", "Title", "INVALID",
                null, null, null, null,
                Instant.now(), "teacher-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("type must be one of");
    }

    @Test
    void create_missingDueDate_throws400() {
        assertThatThrownBy(() -> service.create(
                "class-1", "Title", "PRE_CLASS",
                null, null, null, null,
                null, "teacher-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("dueDate is required");
    }

    // ── startAssignment ─────────────────────────────────────────────

    @Test
    void startAssignment_createsInProgressCompletion() {
        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(assignment));
        when(completionRepo.findByAssignmentIdAndUserId("assign-1", "user-1"))
                .thenReturn(Optional.empty());
        when(completionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentCompletionJpaEntity result = service.startAssignment("assign-1", "user-1");

        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(result.getStartedAt()).isNotNull();
        verify(completionRepo).save(any());
    }

    @Test
    void startAssignment_alreadyCompleted_throws400() {
        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(assignment));

        AssignmentCompletionJpaEntity existing = new AssignmentCompletionJpaEntity();
        existing.setStatus("COMPLETED");
        when(completionRepo.findByAssignmentIdAndUserId("assign-1", "user-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.startAssignment("assign-1", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void startAssignment_notFound_throws404() {
        when(assignmentRepo.findById("bad-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startAssignment("bad-id", "user-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Assignment not found");
    }

    // ── markOverdue ─────────────────────────────────────────────────

    @Test
    void markOverdue_pastDue_marksNonCompletedAsOverdue() {
        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        assignment.setDueDate(Instant.now().minus(1, ChronoUnit.DAYS));
        when(assignmentRepo.findAll()).thenReturn(List.of(assignment));

        AssignmentCompletionJpaEntity pending = new AssignmentCompletionJpaEntity();
        pending.setId("comp-1");
        pending.setStatus("PENDING");

        AssignmentCompletionJpaEntity completed = new AssignmentCompletionJpaEntity();
        completed.setId("comp-2");
        completed.setStatus("COMPLETED");

        when(completionRepo.findByAssignmentId("assign-1"))
                .thenReturn(List.of(pending, completed));
        when(completionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        int marked = service.markOverdue();

        assertThat(marked).isEqualTo(1);
        assertThat(pending.getStatus()).isEqualTo("OVERDUE");
        verify(completionRepo, times(1)).save(pending);
    }

    @Test
    void markOverdue_futureDate_doesNotMark() {
        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        assignment.setDueDate(Instant.now().plus(7, ChronoUnit.DAYS));
        when(assignmentRepo.findAll()).thenReturn(List.of(assignment));

        int marked = service.markOverdue();

        assertThat(marked).isEqualTo(0);
        verify(completionRepo, never()).findByAssignmentId(any());
    }

    // ── checkAndAdvanceCompletions ──────────────────────────────────

    @Test
    void checkAndAdvance_allModulesComplete_marksAssignmentCompleted() {
        AssignmentCompletionJpaEntity completion = new AssignmentCompletionJpaEntity();
        completion.setAssignmentId("assign-1");
        completion.setStatus("IN_PROGRESS");
        when(completionRepo.findByUserId("user-1")).thenReturn(List.of(completion));

        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        assignment.setModuleIds("mod-1");
        assignment.setStages("LEARN,TEST,PROVE");
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(assignment));

        LearningModuleJpaEntity module = buildModule("mod-1", "COMPLETE", 85.0);
        when(moduleRepo.findById("mod-1")).thenReturn(Optional.of(module));
        when(completionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.checkAndAdvanceCompletions("user-1");

        assertThat(completion.getStatus()).isEqualTo("COMPLETED");
        assertThat(completion.getCompletedAt()).isNotNull();
    }

    @Test
    void checkAndAdvance_moduleNotComplete_doesNotAdvance() {
        AssignmentCompletionJpaEntity completion = new AssignmentCompletionJpaEntity();
        completion.setAssignmentId("assign-1");
        completion.setStatus("IN_PROGRESS");
        when(completionRepo.findByUserId("user-1")).thenReturn(List.of(completion));

        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        assignment.setModuleIds("mod-1");
        assignment.setStages("LEARN,TEST,PROVE");
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(assignment));

        LearningModuleJpaEntity module = buildModule("mod-1", "TEST", 0.0);
        when(moduleRepo.findById("mod-1")).thenReturn(Optional.of(module));

        service.checkAndAdvanceCompletions("user-1");

        assertThat(completion.getStatus()).isEqualTo("IN_PROGRESS");
    }

    // ── getDetail ───────────────────────────────────────────────────

    @Test
    void getDetail_returnsCompletionStats() {
        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        assignment.setClassId("class-1");
        assignment.setTitle("Test");
        assignment.setType("PRE_CLASS");
        assignment.setDueDate(Instant.now());
        assignment.setCreatedBy("teacher-1");
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(assignment));

        AssignmentCompletionJpaEntity c1 = new AssignmentCompletionJpaEntity();
        c1.setUserId("u1");
        c1.setStatus("COMPLETED");
        AssignmentCompletionJpaEntity c2 = new AssignmentCompletionJpaEntity();
        c2.setUserId("u2");
        c2.setStatus("PENDING");
        when(completionRepo.findByAssignmentId("assign-1")).thenReturn(List.of(c1, c2));

        Map<String, Object> detail = service.getDetail("assign-1");

        assertThat(detail.get("completedCount")).isEqualTo(1);
        assertThat(detail.get("pendingCount")).isEqualTo(1);
        assertThat(detail.get("title")).isEqualTo("Test");
    }

    @Test
    void getDetail_notFound_throws404() {
        when(assignmentRepo.findById("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail("bad"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    // ── delete ──────────────────────────────────────────────────────

    @Test
    void delete_existing_succeeds() {
        when(assignmentRepo.existsById("assign-1")).thenReturn(true);

        service.delete("assign-1");

        verify(assignmentRepo).deleteById("assign-1");
    }

    @Test
    void delete_notFound_throws404() {
        when(assignmentRepo.existsById("bad")).thenReturn(false);

        assertThatThrownBy(() -> service.delete("bad"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    // ── per-student differentiation (Phase 1 + 3) ───────────────────

    @Test
    void selectWeakModules_keepsOnlyThisStudentsCompletedBelowThreshold_weakestFirst() {
        var m1 = buildModuleFull("m1", "class-1", "avatar-A", "speed", "COMPLETE", 30.0);
        var m2 = buildModuleFull("m2", "class-1", "avatar-A", "area", "COMPLETE", 50.0);
        var mastered = buildModuleFull("m3", "class-1", "avatar-A", "ratio", "COMPLETE", 90.0);
        var notAttempted = buildModuleFull("m4", "class-1", "avatar-A", "frac", "TEST", 10.0);
        when(moduleRepo.findByClassIdAndAvatarId("class-1", "avatar-A"))
                .thenReturn(List.of(m1, m2, mastered, notAttempted));

        List<String> weak = service.selectWeakModulesForStudent(
                "class-1", "avatar-A", 60.0, List.of());

        // 90% mastered and the TEST-stage (not COMPLETE) module are excluded;
        // weakest-first ordering.
        assertThat(weak).containsExactly("m1", "m2");
    }

    @Test
    void selectWeakModules_restrictsToTopicScopeSlugs() {
        var m1 = buildModuleFull("m1", "class-1", "avatar-A", "speed", "COMPLETE", 30.0);
        var m2 = buildModuleFull("m2", "class-1", "avatar-A", "area", "COMPLETE", 40.0);
        when(moduleRepo.findByClassIdAndAvatarId("class-1", "avatar-A"))
                .thenReturn(List.of(m1, m2));

        List<String> weak = service.selectWeakModulesForStudent(
                "class-1", "avatar-A", 60.0, List.of("area"));

        assertThat(weak).containsExactly("m2");
    }

    @Test
    void selectWeakModules_nullAvatar_returnsEmpty() {
        assertThat(service.selectWeakModulesForStudent("class-1", null, 60.0, List.of()))
                .isEmpty();
    }

    @Test
    void startAssignment_personalizedPostClass_snapshotsStudentsOwnWeakModules() {
        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId("assign-1");
        a.setClassId("class-1");
        a.setType("POST_CLASS");
        a.setPersonalized(true);
        a.setMasteryThreshold(BigDecimal.valueOf(60));
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(a));
        when(completionRepo.findByAssignmentIdAndUserId("assign-1", "user-1"))
                .thenReturn(Optional.empty());
        var mem = new ClassMembershipJpaEntity();
        mem.setStudentAvatarId("avatar-A");
        when(membershipRepo.findByClassIdAndUserId("class-1", "user-1"))
                .thenReturn(Optional.of(mem));
        when(moduleRepo.findByClassIdAndAvatarId("class-1", "avatar-A"))
                .thenReturn(List.of(buildModuleFull("m1", "class-1", "avatar-A", "speed", "COMPLETE", 30.0)));
        when(completionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentCompletionJpaEntity result = service.startAssignment("assign-1", "user-1");

        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(result.getResolvedModuleIds()).isEqualTo("m1");
        assertThat(result.getResolvedAt()).isNotNull();
    }

    @Test
    void startAssignment_personalizedPostClass_masteredStudent_autoCompletesWithNoBusywork() {
        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId("assign-1");
        a.setClassId("class-1");
        a.setType("POST_CLASS");
        a.setPersonalized(true);
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(a));
        when(completionRepo.findByAssignmentIdAndUserId("assign-1", "user-1"))
                .thenReturn(Optional.empty());
        var mem = new ClassMembershipJpaEntity();
        mem.setStudentAvatarId("avatar-A");
        when(membershipRepo.findByClassIdAndUserId("class-1", "user-1"))
                .thenReturn(Optional.of(mem));
        // Everything already mastered → no weak modules in scope.
        when(moduleRepo.findByClassIdAndAvatarId("class-1", "avatar-A"))
                .thenReturn(List.of(buildModuleFull("m1", "class-1", "avatar-A", "speed", "COMPLETE", 95.0)));
        when(completionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentCompletionJpaEntity result = service.startAssignment("assign-1", "user-1");

        assertThat(result.getStatus()).isEqualTo("COMPLETED");
        assertThat(result.getResolvedModuleIds()).isEmpty();
        assertThat(result.getScoreSummaryJson()).contains("mastered");
    }

    @Test
    void checkAndAdvance_personalized_gradesAgainstResolvedSet_notClassWide() {
        AssignmentCompletionJpaEntity completion = new AssignmentCompletionJpaEntity();
        completion.setAssignmentId("assign-1");
        completion.setUserId("user-1");
        completion.setStatus("IN_PROGRESS");
        completion.setResolvedModuleIds("mod-1"); // the student's resolved target
        when(completionRepo.findByUserId("user-1")).thenReturn(List.of(completion));

        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setId("assign-1");
        assignment.setPersonalized(true);
        assignment.setModuleIds("mod-CLASS-WIDE-DECOY"); // must NOT be graded against
        assignment.setStages("TEST,PROVE");
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(assignment));
        when(completionRepo.findByAssignmentIdAndUserId("assign-1", "user-1"))
                .thenReturn(Optional.of(completion));
        when(moduleRepo.findById("mod-1"))
                .thenReturn(Optional.of(buildModule("mod-1", "COMPLETE", 85.0)));
        when(completionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.checkAndAdvanceCompletions("user-1");

        assertThat(completion.getStatus()).isEqualTo("COMPLETED");
    }

    // ── list with stats ─────────────────────────────────────────────

    @Test
    void listForClassWithStats_countsCompletedOverdueOutOfActiveStudents() {
        when(membershipRepo.countByClassIdAndStatusAndRole("class-1", "ACTIVE", "STUDENT"))
                .thenReturn(5L);
        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId("a1");
        a.setClassId("class-1");
        a.setTitle("HW1");
        a.setType("POST_CLASS");
        a.setDueDate(Instant.now());
        when(assignmentRepo.findByClassIdOrderByDueDateAsc("class-1")).thenReturn(List.of(a));

        var done = new AssignmentCompletionJpaEntity();
        done.setStatus("COMPLETED");
        var late = new AssignmentCompletionJpaEntity();
        late.setStatus("OVERDUE");
        var inprog = new AssignmentCompletionJpaEntity();
        inprog.setStatus("IN_PROGRESS");
        when(completionRepo.findByAssignmentId("a1")).thenReturn(List.of(done, late, inprog));

        List<Map<String, Object>> list = service.listForClassWithStats("class-1");

        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("completedCount")).isEqualTo(1);
        assertThat(list.get(0).get("overdueCount")).isEqualTo(1);
        assertThat(list.get(0).get("totalStudents")).isEqualTo(5);
    }

    // ── pre-class adaptive diagnostic (Phase 2) ─────────────────────

    @Test
    void startAssignment_personalizedPreClass_givesUniformPrimerPlusWeakPrereqDiagnostic() {
        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId("assign-1");
        a.setClassId("class-1");
        a.setType("PRE_CLASS");
        a.setPersonalized(true);
        a.setModuleIds("primer-1,primer-2");      // uniform new-topic primer
        a.setPrereqScope("fractions,decimals");   // teacher-picked prior topics
        a.setMasteryThreshold(BigDecimal.valueOf(60));
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(a));
        when(completionRepo.findByAssignmentIdAndUserId("assign-1", "user-1"))
                .thenReturn(Optional.empty());
        var mem = new ClassMembershipJpaEntity();
        mem.setStudentAvatarId("avatar-A");
        when(membershipRepo.findByClassIdAndUserId("class-1", "user-1"))
                .thenReturn(Optional.of(mem));
        // Student is weak on "fractions" (a prereq); "decimals" is mastered.
        when(moduleRepo.findByClassIdAndAvatarId("class-1", "avatar-A")).thenReturn(List.of(
                buildModuleFull("diag-frac", "class-1", "avatar-A", "fractions", "COMPLETE", 35.0),
                buildModuleFull("ok-dec", "class-1", "avatar-A", "decimals", "COMPLETE", 88.0)));
        when(completionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssignmentCompletionJpaEntity result = service.startAssignment("assign-1", "user-1");

        // Everyone gets the primer; this student also gets the weak-prereq diagnostic.
        assertThat(result.getResolvedModuleIds()).isEqualTo("primer-1,primer-2,diag-frac");
        assertThat(result.getStatus()).isEqualTo("IN_PROGRESS"); // pre-class never auto-completes
    }

    @Test
    void getReadiness_returnsPerStudentWeakPrereqMap() {
        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId("assign-1");
        a.setClassId("class-1");
        a.setType("PRE_CLASS");
        a.setPrereqScope("fractions");
        a.setMasteryThreshold(BigDecimal.valueOf(60));
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(a));

        var maya = new ClassMembershipJpaEntity();
        maya.setUserId("maya"); maya.setStudentAvatarId("av-maya");
        var noAvatar = new ClassMembershipJpaEntity();
        noAvatar.setUserId("ghost"); // null studentAvatarId → skipped
        when(membershipRepo.findByClassId("class-1")).thenReturn(List.of(maya, noAvatar));
        when(userRepository.findAllByIds(any())).thenReturn(List.of(userNamed("maya", "Maya")));
        when(moduleRepo.findByClassIdAndAvatarId("class-1", "av-maya")).thenReturn(List.of(
                buildModuleFull("m", "class-1", "av-maya", "fractions", "COMPLETE", 40.0)));

        @SuppressWarnings("unchecked")
        var students = (List<Map<String, Object>>) service.getReadiness("assign-1").get("students");

        assertThat(students).hasSize(1); // ghost without an avatar is excluded
        assertThat(students.get(0).get("userId")).isEqualTo("maya");
        assertThat(students.get(0).get("displayName")).isEqualTo("Maya");
        assertThat(students.get(0).get("weakCount")).isEqualTo(1);
    }

    @Test
    void getDetail_enrichesStudentsWithDisplayNamesAndResolvedModuleTitles() {
        AssignmentJpaEntity a = new AssignmentJpaEntity();
        a.setId("assign-1");
        a.setClassId("class-1");
        a.setTitle("HW");
        a.setType("POST_CLASS");
        a.setPersonalized(true);
        a.setDueDate(Instant.now());
        a.setCreatedBy("t");
        when(assignmentRepo.findById("assign-1")).thenReturn(Optional.of(a));

        AssignmentCompletionJpaEntity c = new AssignmentCompletionJpaEntity();
        c.setUserId("maya");
        c.setStatus("IN_PROGRESS");
        c.setResolvedModuleIds("m1,m2");
        when(completionRepo.findByAssignmentId("assign-1")).thenReturn(List.of(c));
        when(userRepository.findAllByIds(any())).thenReturn(List.of(userNamed("maya", "Maya")));

        var m1 = buildModuleFull("m1", "class-1", "av", "speed", "COMPLETE", 30.0);
        m1.setTitle("Speed");
        var m2 = buildModuleFull("m2", "class-1", "av", "percentage", "COMPLETE", 40.0);
        m2.setTitle("Percentage");
        when(moduleRepo.findAllById(any())).thenReturn(List.of(m1, m2));

        @SuppressWarnings("unchecked")
        var students = (List<Map<String, Object>>) service.getDetail("assign-1").get("students");

        assertThat(students).hasSize(1);
        assertThat(students.get(0).get("displayName")).isEqualTo("Maya");
        @SuppressWarnings("unchecked")
        var resolved = (List<Map<String, String>>) students.get(0).get("resolvedModules");
        assertThat(resolved).extracting(mm -> mm.get("title")).containsExactly("Speed", "Percentage");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private User userNamed(String id, String name) {
        User u = new User();
        u.setId(id);
        u.setDisplayName(name);
        return u;
    }

    private LearningModuleJpaEntity buildModuleFull(String id, String classId, String avatarId,
            String slug, String stage, double mastery) {
        LearningModuleJpaEntity m = new LearningModuleJpaEntity();
        m.setId(id);
        m.setClassId(classId);
        m.setAvatarId(avatarId);
        m.setWikiPageSlug(slug);
        m.setTitle("Title");
        m.setStage(stage);
        m.setTier("FREE");
        m.setMasteryPct(BigDecimal.valueOf(mastery));
        m.setCreatedAt(Instant.now());
        return m;
    }

    private LearningModuleJpaEntity buildModule(String id, String stage, double mastery) {
        LearningModuleJpaEntity m = new LearningModuleJpaEntity();
        m.setId(id);
        m.setAvatarId("avatar-1");
        m.setWikiPageSlug("slug");
        m.setTitle("Title");
        m.setStage(stage);
        m.setTier("FREE");
        m.setMasteryPct(BigDecimal.valueOf(mastery));
        m.setCreatedAt(Instant.now());
        return m;
    }
}
