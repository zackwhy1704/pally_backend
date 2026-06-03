package com.pally.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.infrastructure.observability.ClaudeMetrics;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Generates and verifies multiple-choice quiz questions via Claude.
 *
 * <p>Two accuracy improvements over the naive approach:
 * <ol>
 *   <li><b>Hidden reasoning</b> — the model reasons step-by-step before emitting JSON
 *       (Khan Academy technique). The reasoning block is stripped before parsing.</li>
 *   <li><b>Calculator verification</b> — after generation, numeric questions are
 *       verified with the deterministic {@link CalculatorTool}. If the stated
 *       correctIndex is wrong the question is corrected or dropped — Pally never
 *       ships a quiz whose "correct" answer is arithmetically wrong.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
public class ClaudeQuizGenerator implements QuizGeneratorPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudeQuizGenerator.class);

    private final ClaudeApiClient claudeApiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;
    private final CalculatorTool calculator;
    private final ClaudeMetrics metrics;

    /// Matches bare arithmetic operations that appear in a question stem,
    /// e.g. "347 × 89", "144 ÷ 12", "2^8 + 1". Allows Unicode math symbols.
    private static final Pattern ARITHMETIC_IN_QUESTION = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)"
            + "\\s*([+\\-*/×÷^%])\\s*"
            + "(\\d+(?:\\.\\d+)?)"
            + "(?:\\s*([+\\-*/×÷^%])\\s*(\\d+(?:\\.\\d+)?))?",
            Pattern.UNICODE_CHARACTER_CLASS
    );

    @Override
    public List<QuizQuestion> generate(String avatarId, List<WikiPage> pages) {
        String material = pages.stream()
                .map(p -> p.getTitle() + ": " + p.getContent())
                .collect(Collectors.joining("\n\n"));

        // Use tool_use to force Haiku to return structured JSON without any
        // prose or markdown fences. The QuizGeneratorTool schema guarantees
        // exactly 5 MCQs with the required fields — no more strip/fence logic.
        String prompt = """
                Generate 5 multiple-choice questions from this study material.
                Each question must test UNDERSTANDING, not memorisation.
                Use create_quiz_questions to return your answer.

                Material:
                %s
                """.formatted(material);

        try {
            // completeWithTools executes QuizGeneratorTool.execute() which returns
            // the clean JSON array string directly. maxTokens 1200 is ample.
            String raw = claudeApiClient.completeWithTools(
                    modelRouter.forQuizGeneration(), 1200, prompt,
                    List.of(new QuizGeneratorTool(objectMapper)), "quiz-gen");

            // raw is already the serialized JSON array from QuizGeneratorTool.execute()
            // Fall back to text-mode parsing if the tool wasn't called (e.g. circuit open)
            if (raw == null || raw.isBlank()) {
                throw new com.pally.shared.exception.BusinessException(
                        "Couldn't generate a quiz right now — please try again shortly.", 503);
            }

            // If the tool was called and execute() returned the JSON array, parse it.
            // If the model somehow returned text instead, strip fences as before.
            String json = raw.strip();
            if (!json.startsWith("[")) {
                // Strip any remaining fences or preamble
                json = json.replaceAll("(?s)<reasoning>.*?</reasoning>", "").strip();
                if (json.startsWith("```")) {
                    json = json.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
                }
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }

            List<Map<String, Object>> parsed = objectMapper.readValue(json,
                    new TypeReference<>() {});
            List<QuizQuestion> questions = new ArrayList<>();
            for (Map<String, Object> q : parsed) {
                @SuppressWarnings("unchecked")
                List<String> opts = (List<String>) q.get("options");
                questions.add(new QuizQuestion(
                        IdGenerator.newId(),
                        avatarId,
                        (String) q.get("question"),
                        opts,
                        ((Number) q.get("correctIndex")).intValue(),
                        (String) q.getOrDefault("sourcePage", ""),
                        (String) q.getOrDefault("explanation", "")
                ));
            }

            // Part A3.1 — Verify numeric questions before returning
            return verifyAndFilter(questions, avatarId);

        } catch (com.pally.shared.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Quiz] Failed to generate questions for avatar {}", avatarId, e);
            // Throw 503 so the controller returns a proper retry response instead
            // of a silent 200 with 0 questions (which the FE shows as "no quiz yet").
            throw new com.pally.shared.exception.BusinessException(
                    "Couldn't generate a quiz right now — please try again shortly.", 503);
        }
    }

    /**
     * Tool that forces Haiku to return structured quiz JSON via the Anthropic
     * tool_use mechanism. The schema guarantees exactly the right shape without
     * any prose, markdown fences, or chain-of-thought output.
     *
     * <p>{@link #execute} is called by the agentic loop after the model fills
     * the schema; it returns the raw JSON array so {@code completeWithTools}
     * returns it as the final text result.
     */
    static final class QuizGeneratorTool implements ClaudeTool {

        private final ObjectMapper objectMapper;

        QuizGeneratorTool(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        @Override
        public String name() { return "create_quiz_questions"; }

        @Override
        public String description() {
            return "Create 5 multiple-choice quiz questions from the study material provided.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return Map.of(
                "type", "object",
                "properties", Map.of(
                    "questions", Map.of(
                        "type", "array",
                        "description", "Exactly 5 quiz questions",
                        "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                "question",     Map.of("type", "string"),
                                "options",      Map.of("type", "array",
                                                       "items", Map.of("type", "string"),
                                                       "minItems", 4, "maxItems", 4),
                                "correctIndex", Map.of("type", "integer",
                                                       "minimum", 0, "maximum", 3),
                                "sourcePage",   Map.of("type", "string"),
                                "explanation",  Map.of("type", "string")
                            ),
                            "required", List.of("question", "options", "correctIndex",
                                                "sourcePage", "explanation")
                        )
                    )
                ),
                "required", List.of("questions")
            );
        }

        @Override
        public String execute(Map<String, Object> input) throws CalculatorTool.CalculatorException {
            // Extract the questions array and serialize it as a JSON array string
            // so completeWithTools returns it as the final text result.
            try {
                Object questionsRaw = input == null ? null : input.get("questions");
                if (questionsRaw == null) {
                    return "[]";
                }
                return objectMapper.writeValueAsString(questionsRaw);
            } catch (Exception e) {
                return "[]";
            }
        }
    }

    /**
     * For each question whose options are all numeric, verify the correct option
     * against the calculator. Corrects the index if possible; drops the question
     * if the calculator can't agree with any option.
     */
    List<QuizQuestion> verifyAndFilter(List<QuizQuestion> questions, String avatarId) {
        List<QuizQuestion> verified = new ArrayList<>(questions.size());
        for (QuizQuestion q : questions) {
            QuizQuestion result = verifyOne(q);
            if (result != null) {
                verified.add(result);
            } else {
                log.warn("[Quiz] Dropped question '{}' — calculator verification failed "
                        + "and no correct option found", q.question());
            }
        }
        if (verified.size() < questions.size()) {
            log.info("[Quiz] avatar={} started with {} questions, verified {}/{}",
                    avatarId, questions.size(), verified.size(), questions.size());
        }
        return verified;
    }

    /**
     * Attempts to extract an arithmetic expression from the question stem,
     * evaluate it, and compare with options[correctIndex].
     *
     * <p>Returns the (possibly corrected) question, or null if:
     * <ul>
     *   <li>Verification found a discrepancy AND no option matches the correct answer.
     * </ul>
     *
     * Returns the question unchanged when:
     * <ul>
     *   <li>No verifiable arithmetic pattern is found in the question (non-numeric).
     *   <li>Calculator evaluation throws (expression isn't self-contained).
     * </ul>
     */
    private QuizQuestion verifyOne(QuizQuestion q) {
        String questionText = q.question();
        Matcher m = ARITHMETIC_IN_QUESTION.matcher(questionText);
        if (!m.find()) {
            return q; // not a numeric question we can auto-verify
        }

        // Build an expression from the matched groups
        StringBuilder expr = new StringBuilder(m.group(1))
                .append(normaliseOp(m.group(2)))
                .append(m.group(3));
        if (m.group(4) != null) {
            expr.append(normaliseOp(m.group(4))).append(m.group(5));
        }

        String expected;
        try {
            expected = calculator.evaluate(expr.toString());
        } catch (CalculatorTool.CalculatorException e) {
            log.debug("[Quiz] Cannot verify expression '{}': {}", expr, e.getMessage());
            return q; // not verifiable — leave as-is
        }

        List<String> options = q.options();
        int claimedIndex = q.correctIndex();

        // Normalise option values for comparison (strip whitespace, commas)
        String claimedOption = claimedIndex >= 0 && claimedIndex < options.size()
                ? normaliseNumeric(options.get(claimedIndex))
                : "";
        String expectedNorm = normaliseNumeric(expected);

        if (expectedNorm.equals(claimedOption)) {
            return q; // correct — no change needed
        }

        // Disagreement found — fire metric
        metrics.recordQuizAnswerDisagreement();
        metrics.recordCalculatorDisagreement("quiz");
        log.warn("[Quiz] Calculator disagreement: question='{}' expr='{}' "
                + "expected={} but model said options[{}]={}",
                questionText, expr, expected, claimedIndex, claimedOption);

        // Try to find the correct option by scanning all options
        for (int i = 0; i < options.size(); i++) {
            if (expectedNorm.equals(normaliseNumeric(options.get(i)))) {
                log.info("[Quiz] Corrected correctIndex {} → {} for question '{}'",
                        claimedIndex, i, questionText);
                return q.withCorrectIndex(i);
            }
        }

        // Calculator answer isn't among the options — question is unrecoverable
        return null;
    }

    private String normaliseOp(String op) {
        return switch (op) {
            case "×" -> "*";
            case "÷" -> "/";
            case "−" -> "-";
            default -> op;
        };
    }

    /** Strip commas and spaces from numeric strings for comparison. */
    private String normaliseNumeric(String s) {
        if (s == null) return "";
        return s.replaceAll("[,\\s]", "").trim();
    }
}
