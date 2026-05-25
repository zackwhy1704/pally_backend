package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.DetectedTopic;
import com.pally.domain.knowledge.WikiPageIndex;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Uses a fast Claude call to identify which wiki topics are relevant to the user's message.
 * Returns a ranked list of slug keywords for Tier-3 context selection.
 */
@Component
@RequiredArgsConstructor
public class TopicRouter {

    private static final Logger log = LoggerFactory.getLogger(TopicRouter.class);
    private static final int MAX_INDEX_ENTRIES = 40;
    private static final int ROUTER_MAX_TOKENS = 256;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Value("${claude.api.model}")
    private String model;

    public List<DetectedTopic> route(String userMessage, String subject, List<WikiPageIndex> index) {
        if (index.isEmpty()) {
            log.debug("[TopicRouter] Empty index, skipping routing");
            return List.of();
        }

        String indexText = buildIndexText(index);
        String prompt = buildPrompt(userMessage, subject, indexText);

        try {
            String response = apiClient.complete(model, ROUTER_MAX_TOKENS, prompt);
            return parseTopics(response);
        } catch (Exception e) {
            log.warn("[TopicRouter] Routing failed, falling back to empty: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildIndexText(List<WikiPageIndex> index) {
        List<WikiPageIndex> entries = index.size() > MAX_INDEX_ENTRIES
                ? index.subList(0, MAX_INDEX_ENTRIES) : index;
        StringBuilder sb = new StringBuilder();
        for (WikiPageIndex entry : entries) {
            sb.append(entry.slug()).append(": ").append(entry.title()).append('\n');
        }
        return sb.toString().trim();
    }

    private String buildPrompt(String userMessage, String subject, String indexText) {
        return """
                A child is studying %s and asked:
                "%s"

                Available wiki pages (slug: title):
                %s

                Which pages are most relevant to answering this question?
                Reply ONLY with a JSON array, max 5 items, ordered by relevance:
                [{"slug":"page-slug","score":0.9},{"slug":"other-slug","score":0.6}]
                """.formatted(subject, userMessage, indexText);
    }

    private List<DetectedTopic> parseTopics(String response) {
        try {
            String json = extractJson(response);
            JsonNode array = objectMapper.readTree(json);
            List<DetectedTopic> topics = new ArrayList<>();
            for (JsonNode node : array) {
                String slug = node.path("slug").asText("");
                double score = node.path("score").asDouble(0.5);
                if (!slug.isBlank()) {
                    topics.add(new DetectedTopic(slug, score));
                }
            }
            log.debug("[TopicRouter] Detected {} topics: {}", topics.size(), topics);
            return topics;
        } catch (Exception e) {
            log.warn("[TopicRouter] Could not parse topic response: {} — {}", response, e.getMessage());
            return List.of();
        }
    }

    private String extractJson(String response) {
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return response.trim();
    }
}
