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
 * FIX A — Curriculum-specific method rules injected into Block 2.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Singapore MOE curriculum → contains "bar-diagram" and "units".</li>
 *   <li>UK GCSE curriculum → contains "SOH-CAH-TOA".</li>
 *   <li>null curriculum → returns empty string (no extra rules).</li>
 *   <li>Teacher preferences → "TEACHER INSTRUCTIONS" section present when set.</li>
 *   <li>Teacher preferences absent → no "TEACHER INSTRUCTIONS" section when null.</li>
 * </ul>
 *
 * <p>Zero real Claude API calls — all dependencies mocked.
 */
@ExtendWith(MockitoExtension.class)
class CurriculumMethodRulesTest {

    @Mock WikiRepository wikiRepository;
    @Mock com.pally.domain.chat.ChatRepository chatRepository;
    @Mock TopicRouter topicRouter;
    @Mock ChatSessionSummariser sessionSummariser;

    private ClaudeContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ClaudeContextAssembler(
                topicRouter, wikiRepository, chatRepository, new ObjectMapper(),
                sessionSummariser, new CalculatorTool(), new AlgebraTool());

        // Minimal stubs required for assembleSystemBlocks / buildBlock2AvatarConfig
        lenient().when(wikiRepository.getIndex(any())).thenReturn(List.of());
        lenient().when(wikiRepository.findActiveByAvatarId(any())).thenReturn(List.of());
        lenient().when(wikiRepository.findRecentlyArchivedSlugs(any(), any())).thenReturn(List.of());
        lenient().when(sessionSummariser.findSummary(any())).thenReturn(Optional.empty());
    }

    // ── buildCurriculumMethodRules unit tests ─────────────────────────────────

    @Test
    void buildCurriculumMethodRules_singapore_containsBarDiagram() {
        String result = assembler.buildCurriculumMethodRules("SINGAPORE", "P5");
        assertThat(result)
                .as("Singapore MOE rules should mention bar-diagram method")
                .contains("bar-diagram");
    }

    @Test
    void buildCurriculumMethodRules_singapore_containsUnitsLanguage() {
        String result = assembler.buildCurriculumMethodRules("Singapore MOE", "P6");
        assertThat(result)
                .as("Singapore MOE rules should include units-language guidance")
                .contains("units");
    }

    @Test
    void buildCurriculumMethodRules_gcse_containsSohCahToa() {
        String result = assembler.buildCurriculumMethodRules("GCSE", null);
        assertThat(result)
                .as("UK GCSE rules should mention SOH-CAH-TOA")
                .contains("SOH-CAH-TOA");
    }

    @Test
    void buildCurriculumMethodRules_nullCurriculum_returnsEmpty() {
        String result = assembler.buildCurriculumMethodRules(null, null);
        assertThat(result)
                .as("null curriculum should produce empty rules string")
                .isEmpty();
    }

    @Test
    void buildCurriculumMethodRules_unknownCurriculum_returnsEmpty() {
        String result = assembler.buildCurriculumMethodRules("CUSTOM_SCHOOL_SYSTEM", "Year 7");
        assertThat(result)
                .as("Unrecognised curriculum should produce empty rules string (no constraints)")
                .isEmpty();
    }

    @Test
    void buildCurriculumMethodRules_ib_containsSignificantFigures() {
        String result = assembler.buildCurriculumMethodRules("IB", "Grade 10");
        assertThat(result)
                .as("IB rules should mention significant figures")
                .contains("s.f.");
    }

    @Test
    void buildCurriculumMethodRules_aLevel_containsIntegrationPlusC() {
        String result = assembler.buildCurriculumMethodRules("A-LEVEL", null);
        assertThat(result)
                .as("A-Level rules should mention +C for indefinite integrals")
                .contains("+C");
    }

    // ── Teacher preferences injection tests ───────────────────────────────────

    @Test
    void block2_withTeacherPreferences_containsTeacherInstructionsSection() {
        Avatar avatar = buildAvatar("SINGAPORE", "P5", "Always use the model method. No shortcuts.");

        // Build system blocks and extract block 2 (index 1 — block 0 is hard rules)
        List<java.util.Map<String, Object>> blocks = assembler.assembleSystemBlocks(avatar, List.of());
        String block2Text = (String) blocks.get(1).get("text");

        assertThat(block2Text)
                .as("Block 2 should contain TEACHER INSTRUCTIONS when teacherPreferences is set")
                .contains("TEACHER INSTRUCTIONS")
                .contains("Always use the model method. No shortcuts.");
    }

    @Test
    void block2_withNullTeacherPreferences_noTeacherInstructionsSection() {
        Avatar avatar = buildAvatar("SINGAPORE", "P5", null);

        List<java.util.Map<String, Object>> blocks = assembler.assembleSystemBlocks(avatar, List.of());
        String block2Text = (String) blocks.get(1).get("text");

        assertThat(block2Text)
                .as("Block 2 must NOT contain TEACHER INSTRUCTIONS when teacherPreferences is null")
                .doesNotContain("TEACHER INSTRUCTIONS");
    }

    @Test
    void block2_singaporeCurriculum_containsMethodRules() {
        Avatar avatar = buildAvatar("SINGAPORE", "P4", null);

        List<java.util.Map<String, Object>> blocks = assembler.assembleSystemBlocks(avatar, List.of());
        String block2Text = (String) blocks.get(1).get("text");

        assertThat(block2Text)
                .as("Block 2 should contain Singapore curriculum method rules")
                .contains("Singapore MOE")
                .contains("bar-diagram");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Avatar buildAvatar(String curriculum, String grade, String teacherPreferences) {
        Avatar avatar = Avatar.reconstitute(
                "avatar-curriculum-test", "user-1", "Nomi", Subject.MATHS,
                CharacterType.MOCHI, 0, Instant.now(),
                grade, curriculum, Avatar.PedagogyMode.SOCRATIC,
                com.pally.domain.avatar.TeachingMode.TEACHING, null,
                Avatar.BrainState.READY, true, teacherPreferences);
        return avatar;
    }
}
