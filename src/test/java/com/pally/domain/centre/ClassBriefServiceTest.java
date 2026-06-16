package com.pally.domain.centre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.ClaudeApiClient;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.ai.ModelRouter;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaEntity;
import com.pally.infrastructure.persistence.assignment.AssignmentJpaRepository;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaEntity;
import com.pally.infrastructure.persistence.module.ModuleProgressJpaRepository;
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
                geminiCompletion, claudeClient, modelRouter, objectMapper);
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
                Map.of("Student #1", "Alice"),
                1);

        String result = service.generate(inputs);
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
                List.of(), List.of(), Map.of(), 1);

        String result = service.generate(inputs);
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
                List.of(), List.of(), Map.of(), 1);

        assertThatThrownBy(() -> service.generate(inputs))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unparseable");
    }

    // ── getOrGenerate() ───────────────────────────────────────────────────────

    @Test
    void getOrGenerate_cacheFresh_doesNotCallAI() {
        ClassBrief cached = ClassBrief.create("id-1", CLASS_ID, MODULE_ID,
                VALID_BRIEF_JSON, Instant.now());
        when(briefRepo.findByClassIdAndModuleId(CLASS_ID, MODULE_ID))
                .thenReturn(Optional.of(cached));
        when(briefRepo.findMaxProgressCompletedAt(CLASS_ID, MODULE_ID))
                .thenReturn(Optional.of(Instant.now().minusSeconds(3600))); // older than brief
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
}
