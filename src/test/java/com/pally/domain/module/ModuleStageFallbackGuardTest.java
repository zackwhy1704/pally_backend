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

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Map;
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

    // ── B-4 STRENGTHENED (the durable fix) ──────────────────────────────────────
    // A non-empty COUNT is not enough: a blank-but-present item (a SPOT_MISTAKE with
    // problem="" from a single-object generator reading getOrDefault off an empty/partial
    // map) is still ONE item, so the count guard above passes while shipping a broken card.
    // Assert the type's REQUIRED fields are present and SUBSTANTIVE (non-blank) — for BOTH
    // single-object spot-mistake sites (main + centre-regen). This is what catches a
    // blank-item regression regardless of which generator family a future author adds.

    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> json(String raw) throws Exception {
        return raw == null ? Map.of() : mapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
    }

    private void assertField(Map<String, Object> m, String type, String field) {
        assertThat(m.get(field) instanceof String s && !s.isBlank())
                .as("%s item field '%s' is blank/missing — a broken (blank) card shipped", type, field)
                .isTrue();
    }

    private void assertSubstantive(ModuleContentItem item) throws Exception {
        Map<String, Object> c = json(item.getContentJson());
        Map<String, Object> a = json(item.getAnswerJson());
        String t = item.getType();
        switch (ContentItemType.valueOf(t)) {
            case MICRO_CARD -> { assertField(c, t, "title"); assertField(c, t, "body"); }
            case HOT_TAKE -> {
                assertField(c, t, "statement");
                assertThat(a.get("isTrue")).as("HOT_TAKE missing isTrue").isNotNull();
                assertField(a, t, "explanation");
            }
            case SPOT_MISTAKE -> {
                assertField(c, t, "problem"); assertField(c, t, "wrongSolution");
                assertField(a, t, "errorDescription"); assertField(a, t, "correctSolution");
            }
            case CHALLENGE -> {
                assertField(c, t, "question");
                assertField(a, t, "answer"); assertField(a, t, "explanation");
            }
            default -> { /* PROVE has its own generator test */ }
        }
    }

    private void assertEveryStageSubstantive(String modelReply) throws Exception {
        when(gemini.complete(anyInt(), any(), any())).thenReturn(modelReply);
        String content = "Photosynthesis converts light into chemical energy in chloroplasts.";
        for (Supplier<List<ModuleContentItem>> g : allStageGenerators()) {
            for (ModuleContentItem item : g.get()) assertSubstantive(item);
        }
        // SECOND single-object site: the teacher "Regenerate this module" spot-mistake path.
        for (ModuleContentItem item : gen.generateSpotMistakeDraft("m1", content, "P5", "Science", "")) {
            assertSubstantive(item);
        }
    }

    @Test
    void everyStage_emptyModel_shipsSubstantiveContent_notBlankItems() throws Exception {
        assertEveryStageSubstantive("");
    }

    @Test
    void spotMistake_partialParse_fallsBackInsteadOfHalfBlankCard() throws Exception {
        // A PARTIAL parse: a problem with NO answer half. The map is NOT empty, so an
        // isEmpty()-only guard would let it ship a card with no answer to reveal (the
        // answer-split failure class). Pins the FIELD-level guard on BOTH spot-mistake sites.
        assertEveryStageSubstantive("{\"problem\":\"only a problem, no answer half\"}");
    }
}
