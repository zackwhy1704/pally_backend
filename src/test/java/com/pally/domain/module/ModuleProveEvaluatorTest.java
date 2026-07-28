package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.ai.PromptLanguage;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ModuleProveEvaluatorTest {

    @Mock private GeminiCompletionService geminiCompletion;

    private ModuleProveEvaluator evaluator;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        evaluator = new ModuleProveEvaluator(geminiCompletion, objectMapper);
    }

    @Test
    void evaluateAnswer_conceptFullyCovered_returnsHighScore() throws Exception {
        ModuleContentItem item = buildProveItem(
                "Explain photosynthesis", "photosynthesis",
                new String[]{"sunlight", "carbon dioxide", "water"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("""
                        {"conceptCovered":true,"keyPointsHit":["sunlight","carbon dioxide","water"],
                         "keyPointsMissed":[],"feedback":"Great explanation!","score":0.95}
                        """);

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Plants use sunlight and CO2 with water to make food", "en");

        assertThat(result.conceptCovered()).isTrue();
        assertThat(result.keyPointsHit()).containsExactly("sunlight", "carbon dioxide", "water");
        assertThat(result.keyPointsMissed()).isEmpty();
        assertThat(result.score()).isGreaterThanOrEqualTo(0.9);
        assertThat(result.feedback()).isEqualTo("Great explanation!");
    }

    @Test
    void evaluateAnswer_conceptPartiallyCovered_returnsMediumScore() throws Exception {
        ModuleContentItem item = buildProveItem(
                "What is evaporation?", "evaporation",
                new String[]{"liquid to gas", "heat energy", "surface"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("""
                        {"conceptCovered":false,"keyPointsHit":["liquid to gas"],
                         "keyPointsMissed":["heat energy","surface"],"feedback":"Good start!","score":0.4}
                        """);

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Water turns into gas", "en");

        assertThat(result.conceptCovered()).isFalse();
        assertThat(result.keyPointsHit()).hasSize(1);
        assertThat(result.keyPointsMissed()).hasSize(2);
        assertThat(result.score()).isBetween(0.3, 0.5);
    }

    @Test
    void evaluateAnswer_tooShortAnswer_returnsUngradedWithoutCallingLLM() {
        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");

        ModuleProveEvaluator.ProveResult result = evaluator.evaluateAnswer(item, "ok", "en");

        // Never a false 0 — a non-answer is UNGRADED (no signal), not score 0.0.
        assertThat(result.graded()).isFalse();
        assertThat(result.feedback()).contains("more");

        // LLM should NOT have been called
        verifyNoInteractions(geminiCompletion);
    }

    @Test
    void evaluateAnswer_nullAnswer_returnsUngraded() {
        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");

        ModuleProveEvaluator.ProveResult result = evaluator.evaluateAnswer(item, null, "en");

        assertThat(result.graded()).isFalse();
        verifyNoInteractions(geminiCompletion);
    }

    @Test
    void evaluateAnswer_llmReturnsInvalidJson_returnsUngraded_notFalseZero() throws Exception {
        ModuleContentItem item = buildProveItem(
                "What is gravity?", "gravity", new String[]{"force"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("I can't evaluate this right now sorry");

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Gravity pulls things down towards Earth", "en");

        // The fail-open bug: a malformed LLM response must NOT become score 0.0.
        assertThat(result.graded()).isFalse();
        assertThat(result.feedback()).contains("parse");
    }

    @Test
    void evaluateAnswer_validJsonButScoreAbsent_returnsUngraded_neverGraded0() throws Exception {
        // 1b: a schema-invalid "success" — valid JSON with NO score field — must
        // NOT yield graded=true score 0.0. The invariant is that a score-absent
        // response can never produce a graded result, regardless of whether the
        // caller currently reads the score.
        ModuleContentItem item = buildProveItem(
                "What is gravity?", "gravity", new String[]{"force"});
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("{\"conceptCovered\":true,\"feedback\":\"nice work\"}");

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Gravity pulls things down towards Earth", "en");

        assertThat(result.graded()).isFalse();
    }

    @Test
    void evaluateAnswer_scoreNotNumeric_returnsUngraded_neverGraded0() throws Exception {
        // A non-numeric score ("high") is equally schema-invalid → UNGRADED.
        ModuleContentItem item = buildProveItem(
                "What is gravity?", "gravity", new String[]{"force"});
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("{\"conceptCovered\":true,\"score\":\"high\",\"feedback\":\"ok\"}");

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Gravity pulls things down towards Earth", "en");

        assertThat(result.graded()).isFalse();
    }

    @Test
    void evaluateAnswer_scoreClampedTo0And1() throws Exception {
        ModuleContentItem item = buildProveItem(
                "Q", "concept", new String[]{"kp"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("""
                        {"conceptCovered":true,"keyPointsHit":["kp"],"keyPointsMissed":[],
                         "feedback":"Amazing!","score":1.5}
                        """);

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "The answer is clear and correct", "en");

        assertThat(result.score()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void evaluateAnswer_firstAttemptUnparseable_retrySucceeds_returnsGraded() throws Exception {
        // F4: the fragile parser produced avoidable UNGRADEDs on transient model
        // prose. With the robust-JSON retry, a first unparseable response followed by
        // a valid one now GRADES. Fail-without-fix: pre-change there was no retry, so
        // the first unparseable response returned UNGRADED (graded=false).
        ModuleContentItem item = buildProveItem(
                "What is gravity?", "gravity", new String[]{"force"});
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("Sorry — here is my assessment in prose with no JSON at all.");
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval-retry")))
                .thenReturn("""
                        {"conceptCovered":true,"keyPointsHit":["force"],"keyPointsMissed":[],
                         "feedback":"Good","score":0.8}
                        """);

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Gravity is a force that pulls objects toward Earth", "en");

        assertThat(result.graded()).isTrue();
        assertThat(result.score()).isEqualTo(0.8);
        verify(geminiCompletion).complete(anyInt(), anyString(), eq("module-prove-eval-retry"));
    }

    @Test
    void evaluateAnswer_bothAttemptsUnparseable_staysUngraded_afterExactlyOneRetry() throws Exception {
        // UNGRADED remains the terminal fallback — one retry, never a fabricated score.
        ModuleContentItem item = buildProveItem("Q", "concept", new String[]{"kp"});
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("no json here");
        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval-retry")))
                .thenReturn("still no json on the retry either");

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "A reasonable-length answer about the concept", "en");

        assertThat(result.graded()).isFalse();
        verify(geminiCompletion).complete(anyInt(), anyString(), eq("module-prove-eval-retry"));
    }

    @Test
    void feedbackPrompt_isByteIdenticalEn_zhFollowsModuleLanguage() throws Exception {
        // 1b.5b tags the module; ModuleProgressionService passes module.getContentLanguage() here.
        ModuleContentItem item = buildProveItem("What is photosynthesis?", "energy conversion", new String[]{"light", "sugar"});
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        when(geminiCompletion.complete(anyInt(), captor.capture(), anyString()))
                .thenReturn("{\"conceptCovered\":true,\"keyPointsHit\":[],\"keyPointsMissed\":[],\"feedback\":\"ok\",\"score\":0.5}");

        evaluator.evaluateAnswer(item, "Plants turn light into sugar", "en");
        String en = captor.getValue();
        evaluator.evaluateAnswer(item, "Plants turn light into sugar", "zh");
        String zh = captor.getValue();

        assertThat(en).doesNotContain("华语");
        assertThat(zh).isEqualTo(en + PromptLanguage.directive("zh"));
        assertThat(zh).contains("华语");
    }

    private ModuleContentItem buildProveItem(
            String question, String concept, String[] keyPoints) throws Exception {
        ModuleContentItem item = new ModuleContentItem();
        item.setId("item-1");
        item.setModuleId("mod-1");
        item.setStage("PROVE");
        item.setType("PROVE_QUESTION");
        item.setContentJson(objectMapper.writeValueAsString(
                java.util.Map.of("question", question, "targetConcept", concept)));
        item.setAnswerJson(objectMapper.writeValueAsString(
                java.util.Map.of("expectedKeyPoints", java.util.List.of(keyPoints),
                        "targetConcept", concept)));
        return item;
    }
}
