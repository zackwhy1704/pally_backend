package com.pally.domain.module;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.persistence.module.ModuleContentItemJpaEntity;
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
        ModuleContentItemJpaEntity item = buildProveItem(
                "Explain photosynthesis", "photosynthesis",
                new String[]{"sunlight", "carbon dioxide", "water"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("""
                        {"conceptCovered":true,"keyPointsHit":["sunlight","carbon dioxide","water"],
                         "keyPointsMissed":[],"feedback":"Great explanation!","score":0.95}
                        """);

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Plants use sunlight and CO2 with water to make food");

        assertThat(result.conceptCovered()).isTrue();
        assertThat(result.keyPointsHit()).containsExactly("sunlight", "carbon dioxide", "water");
        assertThat(result.keyPointsMissed()).isEmpty();
        assertThat(result.score()).isGreaterThanOrEqualTo(0.9);
        assertThat(result.feedback()).isEqualTo("Great explanation!");
    }

    @Test
    void evaluateAnswer_conceptPartiallyCovered_returnsMediumScore() throws Exception {
        ModuleContentItemJpaEntity item = buildProveItem(
                "What is evaporation?", "evaporation",
                new String[]{"liquid to gas", "heat energy", "surface"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("""
                        {"conceptCovered":false,"keyPointsHit":["liquid to gas"],
                         "keyPointsMissed":["heat energy","surface"],"feedback":"Good start!","score":0.4}
                        """);

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Water turns into gas");

        assertThat(result.conceptCovered()).isFalse();
        assertThat(result.keyPointsHit()).hasSize(1);
        assertThat(result.keyPointsMissed()).hasSize(2);
        assertThat(result.score()).isBetween(0.3, 0.5);
    }

    @Test
    void evaluateAnswer_tooShortAnswer_returnsZeroWithoutCallingLLM() {
        ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
        item.setId("item-1");

        ModuleProveEvaluator.ProveResult result = evaluator.evaluateAnswer(item, "ok");

        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.conceptCovered()).isFalse();
        assertThat(result.feedback()).contains("more");

        // LLM should NOT have been called
        verifyNoInteractions(geminiCompletion);
    }

    @Test
    void evaluateAnswer_nullAnswer_returnsZero() {
        ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
        item.setId("item-1");

        ModuleProveEvaluator.ProveResult result = evaluator.evaluateAnswer(item, null);

        assertThat(result.score()).isEqualTo(0.0);
        verifyNoInteractions(geminiCompletion);
    }

    @Test
    void evaluateAnswer_llmReturnsInvalidJson_returnsGracefulFallback() throws Exception {
        ModuleContentItemJpaEntity item = buildProveItem(
                "What is gravity?", "gravity", new String[]{"force"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("I can't evaluate this right now sorry");

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "Gravity pulls things down towards Earth");

        assertThat(result.score()).isEqualTo(0.0);
        assertThat(result.feedback()).contains("parse");
    }

    @Test
    void evaluateAnswer_scoreClampedTo0And1() throws Exception {
        ModuleContentItemJpaEntity item = buildProveItem(
                "Q", "concept", new String[]{"kp"});

        when(geminiCompletion.complete(anyInt(), anyString(), eq("module-prove-eval")))
                .thenReturn("""
                        {"conceptCovered":true,"keyPointsHit":["kp"],"keyPointsMissed":[],
                         "feedback":"Amazing!","score":1.5}
                        """);

        ModuleProveEvaluator.ProveResult result =
                evaluator.evaluateAnswer(item, "The answer is clear and correct");

        assertThat(result.score()).isLessThanOrEqualTo(1.0);
    }

    private ModuleContentItemJpaEntity buildProveItem(
            String question, String concept, String[] keyPoints) throws Exception {
        ModuleContentItemJpaEntity item = new ModuleContentItemJpaEntity();
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
