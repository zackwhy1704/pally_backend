package com.pally.domain.module;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.infrastructure.ai.GeminiCompletionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Evaluates a student's PROVE answer against the expected key points
 * for that question, using an LLM.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModuleProveEvaluator {

    private static final int MAX_TOKENS = 500;

    private final GeminiCompletionService geminiCompletion;
    private final ObjectMapper objectMapper;

    public record ProveResult(
            boolean conceptCovered,
            List<String> keyPointsHit,
            List<String> keyPointsMissed,
            String feedback,
            double score
    ) {}

    /**
     * Evaluates one PROVE answer against its expected key points.
     *
     * @param proveQuestion the content item with the question and answer_json containing expectedKeyPoints
     * @param studentAnswer the student's free-text answer
     * @return evaluation result
     */
    public ProveResult evaluateAnswer(ModuleContentItem proveQuestion, String studentAnswer) {
        if (studentAnswer == null || studentAnswer.trim().length() < 5) {
            return new ProveResult(false, List.of(), List.of(),
                    "Try writing a bit more to show what you know!", 0.0);
        }

        try {
            // Extract question text and expected key points
            JsonNode contentNode = objectMapper.readTree(proveQuestion.getContentJson());
            String question = contentNode.path("question").asText("");
            String targetConcept = contentNode.path("targetConcept").asText("");

            JsonNode answerNode = objectMapper.readTree(proveQuestion.getAnswerJson());
            List<String> expectedKeyPoints = new ArrayList<>();
            JsonNode kpNode = answerNode.path("expectedKeyPoints");
            if (kpNode.isArray()) {
                for (JsonNode n : kpNode) {
                    expectedKeyPoints.add(n.asText());
                }
            }

            String prompt = """
                    Evaluate this student's answer to a prove-it question.

                    Question: %s
                    Target concept: %s
                    Expected key points: %s
                    Student's answer: %s

                    Reply ONLY with JSON:
                    {"conceptCovered":true,"keyPointsHit":["..."],"keyPointsMissed":["..."],"feedback":"...","score":0.8}
                    """.formatted(
                    question,
                    targetConcept,
                    String.join(", ", expectedKeyPoints),
                    truncate(studentAnswer, 1000));

            String raw = geminiCompletion.complete(MAX_TOKENS, prompt, "module-prove-eval");
            return parseResult(raw);

        } catch (Exception e) {
            log.error("[Module] PROVE evaluation failed for item={}",
                    proveQuestion.getId(), e);
            return new ProveResult(false, List.of(), List.of(),
                    "Could not evaluate your answer. Try again!", 0.0);
        }
    }

    private ProveResult parseResult(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ProveResult(false, List.of(), List.of(),
                    "No evaluation returned.", 0.0);
        }

        String json = raw.strip();
        int first = json.indexOf('{');
        int last = json.lastIndexOf('}');
        if (first < 0 || last <= first) {
            log.warn("[Module] PROVE eval response not valid JSON: {}",
                    raw.substring(0, Math.min(200, raw.length())));
            return new ProveResult(false, List.of(), List.of(),
                    "Could not parse evaluation.", 0.0);
        }
        json = json.substring(first, last + 1);

        try {
            JsonNode node = objectMapper.readTree(json);
            boolean covered = node.path("conceptCovered").asBoolean(false);
            List<String> hit = asStringList(node.path("keyPointsHit"));
            List<String> missed = asStringList(node.path("keyPointsMissed"));
            String feedback = node.path("feedback").asText("Keep trying!");
            double score = node.path("score").asDouble(0.0);
            // Clamp score to [0.0, 1.0]
            score = Math.max(0.0, Math.min(1.0, score));
            return new ProveResult(covered, hit, missed, feedback, score);
        } catch (Exception e) {
            log.warn("[Module] PROVE eval parse failed: {}", e.getMessage());
            return new ProveResult(false, List.of(), List.of(),
                    "Could not parse evaluation.", 0.0);
        }
    }

    private List<String> asStringList(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String s = item.asText();
                if (s != null && !s.isBlank()) out.add(s);
            }
        }
        return out;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
