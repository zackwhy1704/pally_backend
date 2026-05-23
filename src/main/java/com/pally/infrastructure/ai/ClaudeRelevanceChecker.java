package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.RelevanceScore;
import com.pally.domain.knowledge.port.RelevancePort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Claude-backed implementation of {@link RelevancePort}.
 *
 * <p>Sends a structured prompt to Claude asking it to rate content relevance
 * as a JSON object {@code {"score": 0.0–1.0, "reason": "..."}}.
 */
@Component
@RequiredArgsConstructor
public class ClaudeRelevanceChecker implements RelevancePort {

    private static final Logger log = LoggerFactory.getLogger(ClaudeRelevanceChecker.class);
    private static final int MAX_TOKENS = 256;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Value("${claude.api.model}")
    private String model;

    @Override
    public RelevanceScore check(String subject, String wikiSummary, String contentSample) {
        String prompt = buildPrompt(subject, wikiSummary, contentSample);
        log.debug("Sending relevance check for subject={}", subject);

        String raw = apiClient.complete(model, MAX_TOKENS, prompt);
        return parseResponse(raw);
    }

    /**
     * Legacy method kept for backward compatibility with existing use cases.
     * Delegates to {@link #check(String, String, String)}.
     */
    public RelevanceResponse check_legacy(String subject, String wikiSummary, String contentSample) {
        RelevanceScore score = check(subject, wikiSummary, contentSample);
        return new RelevanceResponse(score.value(), score.reason());
    }

    private String buildPrompt(String subject, String wikiSummary, String contentSample) {
        return """
                You are a content relevance evaluator for a children's educational tutoring app.

                The avatar specialises in: %s

                Existing wiki summary:
                %s

                New content sample:
                %s

                Rate how relevant the new content is to the avatar's subject domain.
                Respond ONLY with a JSON object in this exact format (no markdown, no extra text):
                {"score": <0.0 to 1.0>, "reason": "<one sentence explanation>"}
                """.formatted(subject, wikiSummary.isBlank() ? "(none yet)" : wikiSummary, contentSample);
    }

    private RelevanceScore parseResponse(String raw) {
        try {
            // Strip any markdown code fences if present
            String json = raw.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            JsonNode node = objectMapper.readTree(json);
            double score = node.path("score").asDouble(0.0);
            String reason = node.path("reason").asText("No reason provided");
            return new RelevanceScore(score, reason);
        } catch (Exception e) {
            log.error("Failed to parse relevance response: {}", raw, e);
            return new RelevanceScore(0.0, "Parse error: " + e.getMessage());
        }
    }

    /**
     * Legacy response record — preserved so existing use cases that reference
     * {@code ClaudeRelevanceChecker.RelevanceResponse} still compile.
     */
    public record RelevanceResponse(double score, String reason) {}
}
