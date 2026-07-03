package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.groundedness.GroundednessVerifier;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.ai.GeminiCompletionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * SAFETY GUARD for the teacher module-preview. The preview
 * (ModuleProgressionService.getModulePreview) returns each item's contentJson raw
 * and omits answerJson — which is safe ONLY because every generator keeps the
 * correct answer OUT of contentJson (answers live in answerJson). That split is a
 * hand-maintained convention across generators, not an enforced invariant.
 *
 * <p>This pins it mechanically: fed a model response that INCLUDES answer fields,
 * every generator's contentJson must contain NONE of them. A future generator (or
 * a new content type) that copies an answer key into contentJson FAILS here — which
 * also catches the bigger bug, since the STUDENT sees contentJson in the player too,
 * so a leak there exposes answers to students, not just to the preview.
 */
@ExtendWith(MockitoExtension.class)
class ModulePreviewAnswerLeakGuardTest {

    /** Keys that reveal the correct answer — they belong in answerJson, never in contentJson. */
    private static final Set<String> ANSWER_KEYS = Set.of(
            "isTrue", "explanation", "errorDescription", "correctSolution",
            "answer", "correctAnswer", "correctIndex", "correct");

    @Mock GeminiCompletionService gemini;
    @Mock PremiumService premiumService;
    @Mock GroundednessVerifier groundednessVerifier;
    @Mock ModuleWriter moduleWriter;

    private final ObjectMapper mapper = new ObjectMapper();
    ModuleContentGenerator gen;

    @BeforeEach
    void setUp() {
        gen = new ModuleContentGenerator(gemini, mapper, premiumService, groundednessVerifier, moduleWriter);
    }

    private void assertNoAnswerLeak(List<ModuleContentItem> items) throws Exception {
        assertThat(items).isNotEmpty();
        for (ModuleContentItem item : items) {
            @SuppressWarnings("unchecked")
            Map<String, Object> content = mapper.readValue(item.getContentJson(), Map.class);
            assertThat(content.keySet())
                    .as("contentJson for a %s item leaks an answer key (student + preview would see it)",
                            item.getType())
                    .doesNotContainAnyElementsOf(ANSWER_KEYS);
        }
    }

    /** Companion invariant: the answer MUST live in answerJson — else the client reveal
     * (which now reads answerJson) renders blank. Generator + guard + renderer stay in sync. */
    private void assertAnswerInAnswerJson(List<ModuleContentItem> items, String... expectedKeys) throws Exception {
        for (ModuleContentItem item : items) {
            assertThat(item.getAnswerJson())
                    .as("%s must populate answerJson (the reveal reads it)", item.getType())
                    .isNotNull();
            @SuppressWarnings("unchecked")
            Map<String, Object> answer = mapper.readValue(item.getAnswerJson(), Map.class);
            assertThat(answer.keySet()).contains(expectedKeys);
        }
    }

    @Test
    void hotTakes_keepIsTrueAndExplanationOutOfContentJson() throws Exception {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(
                "[{\"statement\":\"Plants eat soil\",\"isTrue\":false,\"explanation\":\"They photosynthesise\"}]");
        List<ModuleContentItem> items = gen.generateHotTakes("m", "content", "P5", "Science", "FREE");
        assertNoAnswerLeak(items);
        assertAnswerInAnswerJson(items, "isTrue", "explanation");
    }

    @Test
    void spotMistake_keepsErrorAndCorrectSolutionOutOfContentJson() throws Exception {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(
                "{\"problem\":\"2+2\",\"wrongSolution\":\"5\",\"errorDescription\":\"added wrong\",\"correctSolution\":\"4\"}");
        List<ModuleContentItem> items = gen.generateSpotMistake("m", "content", "P5", "Science");
        assertNoAnswerLeak(items);
        assertAnswerInAnswerJson(items, "errorDescription", "correctSolution");
    }

    @Test
    void challenges_keepAnswerAndExplanationOutOfContentJson() throws Exception {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(
                "[{\"question\":\"What is 2+2?\",\"answer\":\"4\",\"explanation\":\"add\",\"difficulty\":\"easy\"}]");
        List<ModuleContentItem> items = gen.generateChallenges("m", "content", "P5", "Science", "FREE");
        assertNoAnswerLeak(items);
        assertAnswerInAnswerJson(items, "answer", "explanation");
    }
}
