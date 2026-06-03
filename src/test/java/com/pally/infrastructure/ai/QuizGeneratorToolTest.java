package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for FIX 2 — QuizGeneratorTool (tool_use schema forcing for quiz generation).
 */
class QuizGeneratorToolTest {

    private ClaudeQuizGenerator.QuizGeneratorTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tool = new ClaudeQuizGenerator.QuizGeneratorTool(objectMapper);
    }

    // ── ClaudeTool contract ───────────────────────────────────────────────────

    @Test
    void name_isCreateQuizQuestions() {
        assertThat(tool.name()).isEqualTo("create_quiz_questions");
    }

    @Test
    void description_mentionsMultipleChoiceAndStudyMaterial() {
        assertThat(tool.description())
                .contains("multiple-choice")
                .contains("study material");
    }

    @Test
    void inputSchema_hasQuestionsArrayProperty() {
        Map<String, Object> schema = tool.inputSchema();
        assertThat(schema).containsKey("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertThat(props).containsKey("questions");
    }

    @Test
    void inputSchema_questionsArrayItemsHaveRequiredFields() {
        Map<String, Object> schema = tool.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> questions = (Map<String, Object>) props.get("questions");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) questions.get("items");
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) items.get("required");
        assertThat(required).containsExactlyInAnyOrder(
                "question", "options", "correctIndex", "sourcePage", "explanation");
    }

    @Test
    void inputSchema_optionsHasMinMaxFour() {
        Map<String, Object> schema = tool.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> questions = (Map<String, Object>) props.get("questions");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) questions.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProps = (Map<String, Object>) items.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) itemProps.get("options");
        assertThat(options.get("minItems")).isEqualTo(4);
        assertThat(options.get("maxItems")).isEqualTo(4);
    }

    @Test
    void inputSchema_correctIndexBoundedZeroToThree() {
        Map<String, Object> schema = tool.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> questions = (Map<String, Object>) props.get("questions");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) questions.get("items");
        @SuppressWarnings("unchecked")
        Map<String, Object> itemProps = (Map<String, Object>) items.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> correctIndex = (Map<String, Object>) itemProps.get("correctIndex");
        assertThat(correctIndex.get("minimum")).isEqualTo(0);
        assertThat(correctIndex.get("maximum")).isEqualTo(3);
    }

    // ── execute() serializes questions to JSON ────────────────────────────────

    @Test
    void execute_validQuestions_returnsJsonArray() throws Exception {
        var q = Map.of(
                "question", "What is 2+2?",
                "options", List.of("2", "3", "4", "5"),
                "correctIndex", 2,
                "sourcePage", "arithmetic",
                "explanation", "2+2=4"
        );
        Map<String, Object> input = Map.of("questions", List.of(q));
        String result = tool.execute(input);

        assertThat(result).startsWith("[");
        assertThat(result).contains("What is 2+2?");
        assertThat(result).contains("arithmetic");
    }

    @Test
    void execute_nullInput_returnsEmptyArray() throws Exception {
        String result = tool.execute(null);
        assertThat(result).isEqualTo("[]");
    }

    @Test
    void execute_missingQuestionsKey_returnsEmptyArray() throws Exception {
        String result = tool.execute(Map.of("other", "value"));
        assertThat(result).isEqualTo("[]");
    }

    @Test
    void execute_multipleQuestions_allSerialized() throws Exception {
        var q1 = Map.of("question", "Q1", "options", List.of("A", "B", "C", "D"),
                "correctIndex", 0, "sourcePage", "p1", "explanation", "e1");
        var q2 = Map.of("question", "Q2", "options", List.of("A", "B", "C", "D"),
                "correctIndex", 1, "sourcePage", "p2", "explanation", "e2");
        Map<String, Object> input = Map.of("questions", List.of(q1, q2));

        String result = tool.execute(input);
        var parsed = objectMapper.readTree(result);
        assertThat(parsed.isArray()).isTrue();
        assertThat(parsed.size()).isEqualTo(2);
        assertThat(parsed.get(0).path("question").asText()).isEqualTo("Q1");
        assertThat(parsed.get(1).path("question").asText()).isEqualTo("Q2");
    }
}
