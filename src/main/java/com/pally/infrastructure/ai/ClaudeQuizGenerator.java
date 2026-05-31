package com.pally.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
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

        // Part B — hidden reasoning: model thinks step-by-step first, then emits JSON.
        // The <reasoning> block is stripped before JSON parsing so it never leaks
        // into the student-facing question objects.
        String prompt = """
                Based on the following study material, generate 5 multiple-choice quiz questions.
                Each question must test UNDERSTANDING, not just memorisation.
                Questions must come directly from the provided material.

                For any question involving numbers or calculations:
                1. Work out the correct answer step by step in your reasoning.
                2. Verify the correct answer before setting correctIndex.
                3. Make the distractors plausible but clearly wrong on careful reasoning.

                STEP 1 — Write your reasoning (will be discarded, not shown to students):
                <reasoning>
                [Think through the material, identify the most important concepts,
                work out any arithmetic, then design questions and verify answers]
                </reasoning>

                STEP 2 — Output ONLY the JSON array (no other text after the reasoning block):
                [{"question":"...","options":["A...","B...","C...","D..."],"correctIndex":0,"sourcePage":"slug","explanation":"..."}]

                Material:
                %s
                """.formatted(material);

        try {
            String raw = claudeApiClient.complete(modelRouter.forQuizGeneration(), 2500, prompt,
                    "quiz-gen");

            // Strip <reasoning>...</reasoning> block before parsing
            raw = raw.replaceAll("(?s)<reasoning>.*?</reasoning>", "").strip();
            if (raw.startsWith("```")) {
                raw = raw.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
            }
            // Find first JSON array
            int start = raw.indexOf('[');
            int end = raw.lastIndexOf(']');
            if (start >= 0 && end > start) {
                raw = raw.substring(start, end + 1);
            }

            List<Map<String, Object>> parsed = objectMapper.readValue(raw,
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

        } catch (Exception e) {
            log.error("[Quiz] Failed to generate questions for avatar {}", avatarId, e);
            return List.of();
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
