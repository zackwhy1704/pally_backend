package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.chat.ChatSessionSummariser;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Goal-specific study rules injected into Block 2 of the system prompt.
 *
 * <p>Verifies each of the 5 canonical goal keys (EXAM_PREP, UNIVERSITY,
 * CODING_INTERVIEW, PROFESSIONAL, OTHER) produces the expected instructions,
 * plus backward-compat checks that legacy geography keys still produce
 * non-empty exam-prep rules without breaking.
 */
@ExtendWith(MockitoExtension.class)
class CurriculumMethodRulesTest {

    @Mock WikiRepository wikiRepository;
    @Mock TopicRouter topicRouter;
    @Mock ChatSessionSummariser sessionSummariser;

    private ClaudeContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ClaudeContextAssembler(
                topicRouter, wikiRepository, org.mockito.Mockito.mock(com.pally.domain.weakness.WeaknessProfileService.class), new ObjectMapper(),
                sessionSummariser, new CalculatorTool(), new AlgebraTool());

        lenient().when(wikiRepository.getIndex(any())).thenReturn(List.of());
        lenient().when(wikiRepository.findActiveByAvatarId(any())).thenReturn(List.of());
        lenient().when(wikiRepository.findRecentlyArchivedSlugs(any(), any())).thenReturn(List.of());
        lenient().when(sessionSummariser.findSummary(any())).thenReturn(Optional.empty());
    }

    // ── New canonical goal keys ───────────────────────────────────────────────

    @Test
    void examPrep_containsMarkingKeywords() {
        String result = assembler.buildCurriculumMethodRules("EXAM_PREP", null);
        assertThat(result)
                .as("EXAM_PREP rules should mention exam traps and method marks")
                .contains("method marks")
                .contains("Socratic");
    }

    @Test
    void university_containsUniversityRigour() {
        String result = assembler.buildCurriculumMethodRules("UNIVERSITY", null);
        assertThat(result)
                .as("UNIVERSITY rules should mention university-level rigour and integration +C")
                .contains("university-level")
                .contains("+C");
    }

    @Test
    void codingInterview_containsBigO() {
        String result = assembler.buildCurriculumMethodRules("CODING_INTERVIEW", null);
        assertThat(result)
                .as("CODING_INTERVIEW rules should mention Big-O complexity")
                .contains("Big-O")
                .contains("edge cases");
    }

    @Test
    void professional_containsProfessionalBody() {
        String result = assembler.buildCurriculumMethodRules("PROFESSIONAL", null);
        assertThat(result)
                .as("PROFESSIONAL rules should mention professional body terminology")
                .contains("professional body")
                .contains("CFA");
    }

    @Test
    void nullCurriculum_returnsEmpty() {
        String result = assembler.buildCurriculumMethodRules(null, null);
        assertThat(result)
                .as("null curriculum should produce empty rules string")
                .isEmpty();
    }

    @Test
    void other_returnsEmpty() {
        String result = assembler.buildCurriculumMethodRules("OTHER", null);
        assertThat(result)
                .as("OTHER / unrecognised curriculum produces no constraints")
                .isEmpty();
    }

    @Test
    void unknownCurriculum_returnsEmpty() {
        String result = assembler.buildCurriculumMethodRules("CUSTOM_SCHOOL_SYSTEM", "Year 7");
        assertThat(result)
                .as("Unrecognised curriculum should produce empty rules string")
                .isEmpty();
    }

    // ── Legacy geography keys — backward compat (non-empty, mention exam prep) ─

    @Test
    void legacyCambridge_producesExamPrepRules() {
        String result = assembler.buildCurriculumMethodRules("CAMBRIDGE", "O-Level");
        assertThat(result)
                .as("Legacy CAMBRIDGE key should fall through to exam-prep rules")
                .isNotEmpty()
                .contains("method marks");
    }

    @Test
    void legacyGcse_producesExamPrepRules() {
        String result = assembler.buildCurriculumMethodRules("GCSE", null);
        assertThat(result)
                .as("Legacy GCSE key should fall through to exam-prep rules")
                .isNotEmpty()
                .contains("working steps");
    }

    @Test
    void legacyIb_producesExamPrepRules() {
        String result = assembler.buildCurriculumMethodRules("IB", "Grade 10");
        assertThat(result)
                .as("Legacy IB key should fall through to exam-prep rules")
                .isNotEmpty();
    }

    @Test
    void legacySingapore_producesExamPrepRules() {
        String result = assembler.buildCurriculumMethodRules("SINGAPORE", "P5");
        assertThat(result)
                .as("Legacy SINGAPORE key should fall through to exam-prep rules")
                .isNotEmpty()
                .contains("method marks");
    }

    // ── Teacher preferences injection ─────────────────────────────────────────

    @Test
    void block2_withTeacherPreferences_containsTeacherInstructionsSection() {
        Avatar avatar = buildAvatar("EXAM_PREP", "P5", "Always use the model method. No shortcuts.");

        List<java.util.Map<String, Object>> blocks = assembler.assembleSystemBlocks(avatar, List.of());
        String block2Text = (String) blocks.get(1).get("text");

        assertThat(block2Text)
                .as("Block 2 should contain TEACHER INSTRUCTIONS when teacherPreferences is set")
                .contains("TEACHER INSTRUCTIONS")
                .contains("Always use the model method. No shortcuts.");
    }

    @Test
    void block2_withNullTeacherPreferences_noTeacherInstructionsSection() {
        Avatar avatar = buildAvatar("EXAM_PREP", "P5", null);

        List<java.util.Map<String, Object>> blocks = assembler.assembleSystemBlocks(avatar, List.of());
        String block2Text = (String) blocks.get(1).get("text");

        assertThat(block2Text)
                .as("Block 2 must NOT contain TEACHER INSTRUCTIONS when teacherPreferences is null")
                .doesNotContain("TEACHER INSTRUCTIONS");
    }

    @Test
    void block2_examPrepGoal_containsGoalRules() {
        Avatar avatar = buildAvatar("EXAM_PREP", "Form 4", null);

        List<java.util.Map<String, Object>> blocks = assembler.assembleSystemBlocks(avatar, List.of());
        String block2Text = (String) blocks.get(1).get("text");

        assertThat(block2Text)
                .as("Block 2 should contain Examination Preparation goal rules")
                .contains("Examination Preparation")
                .contains("method marks");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Avatar buildAvatar(String curriculum, String grade, String teacherPreferences) {
        return Avatar.reconstitute(
                "avatar-curriculum-test", "user-1", "Nomi", Subject.MATHS,
                CharacterType.MOCHI, 0, Instant.now(),
                grade, curriculum, Avatar.PedagogyMode.SOCRATIC,
                com.pally.domain.avatar.TeachingMode.TEACHING, null,
                Avatar.BrainState.READY, true, teacherPreferences);
    }
}
