package com.pally.domain.centre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClassBriefServiceTest {

    @Mock ClassBriefRepository briefRepo;
    @Mock ModuleProgressJpaRepository progressRepo;
    @Mock AssignmentJpaRepository assignmentRepo;
    @Mock UserJpaRepository userRepo;
    @Mock GeminiCompletionService geminiCompletion;
    @Mock ClaudeApiClient claudeClient;
    @Mock ModelRouter modelRouter;
    @Mock OrgClassRepository orgClassRepository;
    @Mock com.pally.domain.avatar.AvatarRepository avatarRepository;

    private ClassBriefService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String CLASS_ID = "class-1";
    private static final String MODULE_ID = "module-1";
    private static final String STUDENT_1 = "user-1";
    private static final String STUDENT_2 = "user-2";

    private static final String VALID_BRIEF_JSON = """
            {
              "openWith": "Review quadratics",
              "focusConcepts": [{"name":"Quadratics","failRate":0.8,"failingStudents":["Alice"]}],
              "checkOn": ["Alice"],
              "suggestedGroups": [["Alice","Bob"]],
              "skipLine": null
            }
            """;

    @BeforeEach
    void setUp() {
        service = new ClassBriefService(
                briefRepo, progressRepo, assignmentRepo, userRepo,
                geminiCompletion, claudeClient, modelRouter, objectMapper,
                orgClassRepository, avatarRepository);
    }

    // ── gather() ──────────────────────────────────────────────────────────────

    @Test
    void gather_noStudents_returnsZeroCount() {
        when(briefRepo.findActiveStudentIds(CLASS_ID)).thenReturn(List.of());

        ClassBriefService.BriefInputs inputs = service.gather(CLASS_ID, MODULE_ID);

        assertThat(inputs.studentCount()).isZero();
        assertThat(inputs.conceptSignals()).isEmpty();
    }

    @Test
    void gather_worstConceptSortedFirst() {
        when(briefRepo.findActiveStudentIds(CLASS_ID)).thenReturn(List.of(STUDENT_1, STUDENT_2));
        when(userRepo.findAllById(any())).thenReturn(List.of());
        when(assignmentRepo.findByClassId(CLASS_ID)).thenReturn(List.of());

        // Two concepts: "Algebra" 1/1 failed (100%), "Biology" 0/1 failed (0%)
        ModuleProgressJpaEntity rowFail = makeProgress(STUDENT_1, MODULE_ID, "Algebra", 40.0);
        ModuleProgressJpaEntity rowPass = makeProgress(STUDENT_2, MODULE_ID, "Biology", 80.0);
        when(progressRepo.findByModuleIdInAndUserIdIn(any(), any()))
                .thenReturn(List.of(rowFail, rowPass));

        ClassBriefService.BriefInputs inputs = service.gather(CLASS_ID, MODULE_ID);

        assertThat(inputs.conceptSignals()).hasSize(2);
        // Algebra (100% fail) must be first
        assertThat(inputs.conceptSignals().get(0).concept()).isEqualTo("Algebra");
        assertThat(inputs.conceptSignals().get(0).failRate()).isEqualTo(1.0);
        // Biology (0% fail) second
        assertThat(inputs.conceptSignals().get(1).concept()).isEqualTo("Biology");
        assertThat(inputs.conceptSignals().get(1).failRate()).isEqualTo(0.0);
    }

    @Test
    void gather_masteryThresholdFromAssignment() {
        when(briefRepo.findActiveStudentIds(CLASS_ID)).thenReturn(List.of(STUDENT_1));
        when(userRepo.findAllById(any())).thenReturn(List.of());

        AssignmentJpaEntity assignment = new AssignmentJpaEntity();
        assignment.setModuleIds("[\"" + MODULE_ID + "\"]");
        assignment.setMasteryThreshold(new BigDecimal("75.0"));
        when(assignmentRepo.findByClassId(CLASS_ID)).thenReturn(List.of(assignment));

        // Score 70 → below 75 threshold → should fail
        ModuleProgressJpaEntity row = makeProgress(STUDENT_1, MODULE_ID, "Concept", 70.0);
        when(progressRepo.findByModuleIdInAndUserIdIn(any(), any()))
                .thenReturn(List.of(row));

        ClassBriefService.BriefInputs inputs = service.gather(CLASS_ID, MODULE_ID);

        assertThat(inputs.conceptSignals().get(0).failRate()).isEqualTo(1.0);
    }

    // ── generate() ────────────────────────────────────────────────────────────

    @Test
    void generate_validGeminiResponse_returnsJson() {
        when(geminiCompletion.complete(anyInt(), anyString(), eq("class-brief")))
                .thenReturn(VALID_BRIEF_JSON);

        ClassBriefService.BriefInputs inputs = new ClassBriefService.BriefInputs(
                List.of(new ClassBriefService.ConceptSignal("Quadratics", 0.8, List.of("Student #1"))),
                List.of(new ClassBriefService.StudentSignal("Student #1", 0.2, List.of("Quadratics"))),
                Map.of("user-alice", "Student #1"),   // anonById
                Map.of("Student #1", "Alice"),          // nameByAnon
                1);

        String result = service.generate(inputs, "en");
        assertThat(result).contains("openWith");
    }

    @Test
    void generate_geminiReturnsBadJson_retriesWithHaiku() {
        when(geminiCompletion.complete(anyInt(), anyString(), eq("class-brief")))
                .thenReturn("Sorry, I can't help with that.");
        when(modelRouter.getHaikuModel()).thenReturn("claude-haiku-4-5-20251001");
        when(claudeClient.complete(anyString(), anyInt(), anyString(), eq("class-brief")))
                .thenReturn(VALID_BRIEF_JSON);

        ClassBriefService.BriefInputs inputs = new ClassBriefService.BriefInputs(
                List.of(), List.of(), Map.of(), Map.of(), 1);

        String result = service.generate(inputs, "en");
        assertThat(result).contains("openWith");
        verify(claudeClient).complete(anyString(), anyInt(), anyString(), eq("class-brief"));
    }

    @Test
    void generate_bothModelsFail_throwsBusinessException() {
        when(geminiCompletion.complete(anyInt(), anyString(), eq("class-brief")))
                .thenReturn("not json at all");
        when(modelRouter.getHaikuModel()).thenReturn("claude-haiku-4-5-20251001");
        when(claudeClient.complete(anyString(), anyInt(), anyString(), eq("class-brief")))
                .thenReturn("also not json");

        ClassBriefService.BriefInputs inputs = new ClassBriefService.BriefInputs(
                List.of(), List.of(), Map.of(), Map.of(), 1);

        assertThatThrownBy(() -> service.generate(inputs, "en"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unparseable");
    }

    // ── getOrGenerate() ───────────────────────────────────────────────────────

    @Test
    void getOrGenerate_cacheFresh_doesNotCallAI() {
        // null anonMapJson → legacy path (fallback to current roster)
        ClassBrief cached = ClassBrief.create("id-1", CLASS_ID, MODULE_ID,
                VALID_BRIEF_JSON, null, Instant.now());
        when(briefRepo.findByClassIdAndModuleId(CLASS_ID, MODULE_ID))
                .thenReturn(Optional.of(cached));
        when(briefRepo.findMaxProgressCompletedAt(CLASS_ID, MODULE_ID))
                .thenReturn(Optional.of(Instant.now().minusSeconds(3600))); // older than brief
        // Legacy fallback calls these to rebuild anon map from current roster
        when(briefRepo.findActiveStudentIds(CLASS_ID)).thenReturn(List.of(STUDENT_1));
        when(userRepo.findAllById(any())).thenReturn(List.of());

        service.getOrGenerate(CLASS_ID, MODULE_ID);

        verify(geminiCompletion, never()).complete(anyInt(), anyString(), anyString());
    }

    @Test
    void getOrGenerate_noStudents_throwsBusinessException() {
        when(briefRepo.findByClassIdAndModuleId(CLASS_ID, MODULE_ID))
                .thenReturn(Optional.empty());
        when(briefRepo.findActiveStudentIds(CLASS_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getOrGenerate(CLASS_ID, MODULE_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No students");
    }

    /**
     * B1 regression: roster changes between generate and read must not shift identities.
     * Alice was "Student #1" when the brief was generated. Aaron joins later and would be
     * first alphabetically on the current roster. The stored anon map must ensure Alice
     * is still resolved as the flagged student, not Aaron.
     */
    @Test
    void getOrGenerate_rosterChangedAfterGeneration_storedAnonMapPreservesIdentity()
            throws Exception {
        String aliceId = "user-alice";
        String bobId   = "user-bob";
        String carolId = "user-carol";

        // Anon map captured at generation: Alice = #1, Bob = #2, Carol = #3
        String storedAnonMap = objectMapper.writeValueAsString(Map.of(
                aliceId, "Student #1",
                bobId,   "Student #2",
                carolId, "Student #3"));

        String briefJson = """
                {
                  "openWith": "Review",
                  "focusConcepts": [],
                  "checkOn": ["Student #1"],
                  "suggestedGroups": [],
                  "skipLine": null
                }
                """;

        ClassBrief cached = ClassBrief.create(
                "id-1", CLASS_ID, MODULE_ID, briefJson, storedAnonMap, Instant.now());
        when(briefRepo.findByClassIdAndModuleId(CLASS_ID, MODULE_ID))
                .thenReturn(Optional.of(cached));
        // Cache is still fresh (no new progress)
        when(briefRepo.findMaxProgressCompletedAt(CLASS_ID, MODULE_ID))
                .thenReturn(Optional.of(Instant.now().minusSeconds(3600)));

        // Fresh name lookup returns Alice by her userId (Aaron is NOT here — wasn't in original map)
        UserJpaEntity alice = makeUser(aliceId, "Alice");
        UserJpaEntity bob   = makeUser(bobId,   "Bob");
        UserJpaEntity carol = makeUser(carolId, "Carol");
        when(userRepo.findAllById(any())).thenReturn(List.of(alice, bob, carol));

        Map<String, Object> result = service.getOrGenerate(CLASS_ID, MODULE_ID);

        @SuppressWarnings("unchecked")
        List<String> checkOn = (List<String>) result.get("checkOn");
        assertThat(checkOn)
                .as("Student #1 should resolve to Alice (stored at generation), not Aaron")
                .containsExactly("Alice")
                .doesNotContain("Aaron");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private ModuleProgressJpaEntity makeProgress(
            String userId, String moduleId, String concept, double score) {
        ModuleProgressJpaEntity e = new ModuleProgressJpaEntity();
        e.setUserId(userId);
        e.setModuleId(moduleId);
        e.setTargetConcept(concept);
        e.setScore(new BigDecimal(String.valueOf(score)));
        e.setStage("TEST");
        return e;
    }

    private UserJpaEntity makeUser(String id, String displayName) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        u.setDisplayName(displayName);
        return u;
    }
}
