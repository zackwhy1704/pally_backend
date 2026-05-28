package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.api.teach.dto.TeachResponse;
import com.pally.domain.knowledge.WikiPage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Grades a child's free-text explanation of a topic against the avatar's
 * wiki page. The wiki content is the ground truth — Claude extracts the
 * salient concepts, then checks which ones the explanation covers.
 *
 * <p>Designed for the Feynman-technique "Teach Mochi" flow: students explain
 * a topic in their own words and get specific, concept-level feedback rather
 * than a vague "good job".
 */
@Component
@RequiredArgsConstructor
public class ClaudeTeachEvaluator {

    private static final Logger log =
            LoggerFactory.getLogger(ClaudeTeachEvaluator.class);
    private static final int MAX_TOKENS = 700;
    private static final int XP_PER_COVERED = 5;
    private static final int PERFECT_BONUS = 10;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;

    public TeachResponse evaluate(WikiPage page, String explanation) {
        if (explanation == null || explanation.trim().length() < 10) {
            return new TeachResponse(
                    0, 0, 0, List.of(), List.of(),
                    "Try writing a bit more — even one sentence per idea helps!",
                    "Your explanation was too short to evaluate.");
        }

        String prompt = buildPrompt(page, explanation);
        long start = System.currentTimeMillis();
        String raw;
        try {
            raw = apiClient.complete(
                    modelRouter.getHaikuModel(), MAX_TOKENS, prompt);
        } catch (Exception e) {
            log.warn("[Teach] Claude call failed: {}", e.getMessage());
            return new TeachResponse(0, 0, 0, List.of(), List.of(),
                    null, "I had trouble evaluating that. Try again in a moment!");
        }
        log.info("[Teach] evaluated slug={} ms={} chars={}",
                page.getSlug(), System.currentTimeMillis() - start,
                raw == null ? 0 : raw.length());

        return parseResponse(raw);
    }

    private String buildPrompt(WikiPage page, String explanation) {
        return """
                You are evaluating how well a 8–14 year old student explained a topic
                in their own words, Feynman-technique style. Your job is to identify
                the concepts they covered vs missed, then return STRICT JSON.

                ## Topic (ground truth — drawn from the student's own study notes)
                Title: %s
                Content:
                %s

                ## Student's explanation
                %s

                ## Task
                1. Extract the 4–8 key concepts from the topic content.
                2. For each concept, decide whether the student's explanation covers
                   it (even informally / with examples counts as covered).
                3. Pick ONE concept the student missed (largest gap first) and write
                   a friendly Socratic question for it — a question that nudges the
                   student to discover the answer themselves, not the answer itself.
                4. Write 1–2 sentences of warm, specific encouragement.

                Reply with ONLY this JSON shape, no markdown fence:
                {
                  "covered": ["concept name", ...],
                  "missed":  ["concept name", ...],
                  "followUpQuestion": "...",
                  "feedback": "..."
                }
                """.formatted(
                page.getTitle(),
                truncate(page.getContent(), 2500),
                truncate(explanation, 1500));
    }

    private TeachResponse parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new TeachResponse(0, 0, 0, List.of(), List.of(),
                    null, "No feedback returned — try again.");
        }
        // Trim common preamble or markdown fences just in case the model
        // adds them despite the prompt.
        String json = raw.trim();
        int first = json.indexOf('{');
        int last = json.lastIndexOf('}');
        if (first < 0 || last <= first) {
            log.warn("[Teach] response not valid JSON: {}",
                    raw.substring(0, Math.min(200, raw.length())));
            return new TeachResponse(0, 0, 0, List.of(), List.of(),
                    null, "Could not parse feedback.");
        }
        json = json.substring(first, last + 1);

        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> covered = asStringList(node.path("covered"));
            List<String> missed = asStringList(node.path("missed"));
            String followUp = node.path("followUpQuestion").asText(null);
            String feedback = node.path("feedback").asText(
                    "Nice work — keep practising!");

            int total = covered.size() + missed.size();
            int score = covered.size();
            int xp = score * XP_PER_COVERED
                    + (missed.isEmpty() && total > 0 ? PERFECT_BONUS : 0);

            return new TeachResponse(score, total, xp, covered, missed,
                    followUp == null || followUp.isBlank() ? null : followUp,
                    feedback);
        } catch (Exception e) {
            log.warn("[Teach] parse failed: {}", e.getMessage());
            return new TeachResponse(0, 0, 0, List.of(), List.of(),
                    null, "Could not parse feedback.");
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
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
