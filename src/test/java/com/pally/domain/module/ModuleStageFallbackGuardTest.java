package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.domain.knowledge.groundedness.GroundednessVerifier;
import com.pally.domain.subscription.PremiumService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * FAMILY GUARD (the "fix the family, not the instance" lesson, mechanized): EVERY
 * module-stage generator must return ≥1 item even when the model degrades to empty
 * / prose / truncated output — so no stage ever ships empty and strands the student
 * (the empty-LEARN class). PROVE already had this; a new stage generator that
 * returns List.of() on empty will FAIL this test. Do not weaken it.
 */
@ExtendWith(MockitoExtension.class)
class ModuleStageFallbackGuardTest {

    @Mock GeminiCompletionService gemini;
    @Mock PremiumService premiumService;
    @Mock GroundednessVerifier groundednessVerifier;
    @Mock ModuleWriter moduleWriter;

    ModuleContentGenerator gen;

    @BeforeEach
    void setUp() {
        gen = new ModuleContentGenerator(gemini, new ObjectMapper(),
                premiumService, groundednessVerifier, moduleWriter);
    }

    // Each stage generator, invoked with real content.
    private List<Supplier<List<ModuleContentItem>>> allStageGenerators() {
        String content = "Photosynthesis converts light into chemical energy in chloroplasts.";
        return List.of(
                () -> gen.generateMicroCards("m1", content, "P5", "Science", "FREE"),   // LEARN
                () -> gen.generateHotTakes("m1", content, "P5", "Science", "FREE"),      // TEST
                () -> gen.generateSpotMistake("m1", content, "P5", "Science"),           // TEST
                () -> gen.generateChallenges("m1", content, "P5", "Science", "FREE"));   // TEST
    }

    private void assertEveryStageNonEmpty(String modelReply) {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(modelReply);
        for (Supplier<List<ModuleContentItem>> g : allStageGenerators()) {
            List<ModuleContentItem> items = g.get();
            assertThat(items)
                    .as("a stage generator returned EMPTY on model reply=<%s> — "
                            + "add a fallback (no stage may ship empty)", modelReply)
                    .isNotEmpty();
        }
    }

    @Test
    void everyStage_survivesEmptyModelOutput() {
        assertEveryStageNonEmpty("");
    }

    @Test
    void everyStage_survivesProseInsteadOfJson() {
        assertEveryStageNonEmpty("Sure! Here are some great questions for the student:");
    }

    @Test
    void everyStage_survivesTruncatedJson() {
        assertEveryStageNonEmpty("```json\n[{\"title\":\"Photosynthesis\",\"body\":\"It conv");
    }
}
