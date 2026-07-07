package com.pally.domain.content;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.marking.MarkingReference;
import com.pally.domain.marking.MarkingReferenceFile;
import com.pally.domain.marking.MarkingReferenceKind;
import com.pally.domain.module.ContentItemType;
import com.pally.domain.module.ModuleContentItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 OutputValidator rules — proves it REJECTS unusable output (not just passes),
 * across all three OutputTypes, and that coverage is STRUCTURAL (no type silently defaults
 * to pass-through). Mirrors the B-4 substantive contract at the persist boundary.
 */
class RulesOutputValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RulesOutputValidator validator = new RulesOutputValidator(mapper);

    // ── MODULE_ITEM ──────────────────────────────────────────────────────────────
    @Test
    void moduleItem_blankSpotMistake_isDropped_validIsRetained() {
        ModuleContentItem blank = spotMistake("", "", "", "");
        ModuleContentItem valid = spotMistake(
                "Solve 2+2", "2+2=5", "addition slip", "2+2=4");
        assertThat(validator.retainValid(List.of(blank, valid), OutputType.MODULE_ITEM))
                .as("a SPOT_MISTAKE with all-blank fields is dropped; the valid one survives")
                .containsExactly(valid);
    }

    @Test
    void moduleItem_halfBlankSpotMistake_isDropped() {
        // The partial-parse failure mode: present but half-empty (a real regression class).
        ModuleContentItem halfBlank = spotMistake("Solve 2+2", "", "addition slip", "");
        assertThat(validator.retainValid(List.of(halfBlank), OutputType.MODULE_ITEM))
                .as("a half-blank SPOT_MISTAKE (missing wrongSolution/correctSolution) is dropped")
                .isEmpty();
    }

    @Test
    void moduleItem_blankChallengeStem_isDropped() {
        ModuleContentItem blank = challenge("", "the answer", "why");
        ModuleContentItem valid = challenge("Explain photosynthesis.", "converts light", "why");
        assertThat(validator.retainValid(List.of(blank, valid), OutputType.MODULE_ITEM))
                .containsExactly(valid);
    }

    @Test
    void moduleItem_microCardMissingBody_isDropped() {
        ModuleContentItem blank = microCard("Title", "");
        ModuleContentItem valid = microCard("Photosynthesis", "Plants convert light to energy.");
        assertThat(validator.retainValid(List.of(blank, valid), OutputType.MODULE_ITEM))
                .containsExactly(valid);
    }

    @Test
    void moduleItem_blankProveQuestion_isDropped_becauseProveGatesCompletion() {
        ModuleContentItem blank = proveQuestion("");
        ModuleContentItem valid = proveQuestion("Explain, in your own words, why leaves are green.");
        assertThat(validator.retainValid(List.of(blank, valid), OutputType.MODULE_ITEM))
                .containsExactly(valid);
    }

    @Test
    void moduleItem_unparseableOrUnknownType_isDropped() {
        ModuleContentItem badJson = new ModuleContentItem();
        badJson.setType(ContentItemType.MICRO_CARD.name());
        badJson.setContentJson("{not json");
        ModuleContentItem unknownType = new ModuleContentItem();
        unknownType.setType("NONSENSE");
        unknownType.setContentJson("{}");
        assertThat(validator.retainValid(List.of(badJson, unknownType), OutputType.MODULE_ITEM))
                .isEmpty();
    }

    // ── REPORT ───────────────────────────────────────────────────────────────────
    @Test
    void report_blankOrTooShort_isDropped_validNarrativeAndNoDataMessageRetained() {
        String blank = "   ";
        String tooShort = "n/a";
        String noData = "No quiz data yet — students need to complete at least one lesson "
                + "before a report can be generated.";
        String narrative = "The class is strong on fractions but struggles with ratios; "
                + "recommend a revision session before the exam.";
        assertThat(validator.retainValid(List.of(blank, tooShort, noData, narrative), OutputType.REPORT))
                .as("blank/too-short reports drop; a real narrative AND the valid no-data message survive")
                .containsExactly(noData, narrative);
    }

    // ── MARKING_REFERENCE (the corrected rule) ─────────────────────────────────────
    @Test
    void markingReference_textlessButWithAFile_isRETAINED_perDocumentedDesign() {
        // MarkingReferenceService deliberately allows a text-less (e.g. unreadable scan)
        // reference — the file is still a viewable artifact. Dropping it would be a
        // regression, so the rule is "text OR file", not "has text".
        MarkingReference textlessWithFile = MarkingReference.create(
                "class-1", MarkingReferenceKind.MARKED_PAPER, "Scanned paper", null,
                List.of(new MarkingReferenceFile("k", "paper.pdf", "application/pdf", 1024)),
                "");
        assertThat(validator.retainValid(List.of(textlessWithFile), OutputType.MARKING_REFERENCE))
                .as("a text-less reference WITH a stored file is valid by design")
                .containsExactly(textlessWithFile);
    }

    @Test
    void markingReference_noTextAndNoFile_isDropped() {
        MarkingReference empty = MarkingReference.create(
                "class-1", MarkingReferenceKind.RUBRIC, "Empty", null, List.of(), "");
        assertThat(validator.retainValid(List.of(empty), OutputType.MARKING_REFERENCE))
                .as("a reference with neither text nor a file carries no signal — dropped")
                .isEmpty();
    }

    // ── STRUCTURAL COVERAGE GUARD ──────────────────────────────────────────────────
    // The load-bearing guard: every OutputType must have a REAL rule that can reject a
    // known-empty input. If a new type is added and defaults to pass-through, its
    // representative empty input would survive and this fails. (The exhaustive switch in
    // RulesOutputValidator already makes a missing case a COMPILE error; this pins the
    // runtime behaviour too.)
    @Test
    void everyOutputType_hasANonTrivialRule_thatRejectsAKnownEmptyInput() {
        for (OutputType type : OutputType.values()) {
            Object empty = representativeEmpty(type);
            assertThat(validator.retainValid(List.of(empty), type))
                    .as("OutputType %s must reject a known-empty input — if it passes, it is "
                            + "silently pass-through (the blind spot that shipped blank drafts)", type)
                    .isEmpty();
        }
    }

    private Object representativeEmpty(OutputType type) {
        return switch (type) {
            case MODULE_ITEM -> microCard("", "");
            case REPORT -> "";
            case MARKING_REFERENCE -> MarkingReference.create(
                    "c", MarkingReferenceKind.GUIDELINE, "t", null, List.of(), "");
        };
    }

    // ── builders ───────────────────────────────────────────────────────────────────
    private ModuleContentItem item(ContentItemType type, Map<String, Object> content,
                                   Map<String, Object> answer) {
        ModuleContentItem i = new ModuleContentItem();
        i.setType(type.name());
        try {
            i.setContentJson(mapper.writeValueAsString(content));
            if (answer != null) i.setAnswerJson(mapper.writeValueAsString(answer));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return i;
    }

    private ModuleContentItem spotMistake(String problem, String wrong, String err, String correct) {
        return item(ContentItemType.SPOT_MISTAKE,
                Map.of("problem", problem, "wrongSolution", wrong),
                Map.of("errorDescription", err, "correctSolution", correct));
    }

    private ModuleContentItem challenge(String question, String answer, String explanation) {
        return item(ContentItemType.CHALLENGE,
                Map.of("question", question),
                Map.of("answer", answer, "explanation", explanation));
    }

    private ModuleContentItem microCard(String title, String body) {
        return item(ContentItemType.MICRO_CARD, Map.of("title", title, "body", body), null);
    }

    private ModuleContentItem proveQuestion(String question) {
        return item(ContentItemType.PROVE_QUESTION, Map.of("question", question),
                Map.of("expectedKeyPoints", List.of(), "targetConcept", ""));
    }
}
