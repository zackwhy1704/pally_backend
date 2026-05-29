package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a free-text syllabus (PDF text, paste, image-OCRed) into a flat
 * ordered list of topic names + slugs ready to be persisted as
 * {@code curriculum_topics}. Single Haiku call, JSON-constrained output.
 */
@Component
@RequiredArgsConstructor
public class ClaudeSyllabusParser {

    private static final Logger log =
            LoggerFactory.getLogger(ClaudeSyllabusParser.class);
    private static final int MAX_TOKENS = 1500;
    private static final int MAX_INPUT_CHARS = 4000;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;

    public record ParsedTopic(String name, String slug) {}

    public List<ParsedTopic> parse(String subject, String rawText) {
        if (rawText == null || rawText.isBlank()) return List.of();

        String prompt = """
                You are extracting a curriculum topic list from a
                school syllabus document.

                Subject: %s

                Syllabus text:
                %s

                ## Task
                Return a flat ordered list of distinct topics taught
                across this syllabus (no chapter/section headers, no
                duplicates). Each topic should be specific enough that
                a child could quiz themselves on it (e.g. "Photosynthesis"
                rather than "Plants").

                Reply with ONLY a JSON array, no markdown:
                [{"name":"Topic name", "slug":"lowercase-hyphenated"}, ...]

                Aim for 10–40 topics. Slugs must be URL-safe and unique.
                """.formatted(subject, truncate(rawText, MAX_INPUT_CHARS));

        String raw;
        try {
            raw = apiClient.complete(
                    modelRouter.getHaikuModel(), MAX_TOKENS, prompt);
        } catch (Exception e) {
            log.warn("[Syllabus] Claude call failed: {}", e.getMessage());
            return List.of();
        }
        if (raw == null || raw.isBlank()) return List.of();

        String json = raw.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("```[a-z]*\\n?", "")
                    .replaceAll("```", "")
                    .strip();
        }
        int first = json.indexOf('[');
        int last = json.lastIndexOf(']');
        if (first < 0 || last <= first) return List.of();
        json = json.substring(first, last + 1);

        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) return List.of();
            List<ParsedTopic> out = new ArrayList<>();
            java.util.Set<String> seenSlugs = new java.util.HashSet<>();
            for (JsonNode n : arr) {
                String name = n.path("name").asText("").trim();
                String slug = n.path("slug").asText("").trim().toLowerCase();
                if (name.isBlank() || slug.isBlank()) continue;
                if (!seenSlugs.add(slug)) continue;
                out.add(new ParsedTopic(name, slug));
            }
            log.info("[Syllabus] parsed {} topics for subject={}",
                    out.size(), subject);
            return out;
        } catch (Exception e) {
            log.warn("[Syllabus] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
