package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Gemini 2.0 Flash implementation of {@link WikiCompilerPort}.
 *
 * <p>Marked {@code @Primary} so it replaces {@link ClaudeWikiCompiler} for wiki
 * compilation. Claude remains the implementation for everything else (chat, quiz,
 * relevance). Falls back to Claude automatically when the Gemini key is missing
 * or when a Gemini call fails, so a misconfigured key degrades gracefully.
 *
 * <p>Why Gemini Flash for wiki compile:
 * <ul>
 *   <li>Input cost: $0.075/M tokens vs Haiku $0.80/M — 10× cheaper</li>
 *   <li>1M token context window — an entire textbook in one call, no chunking</li>
 *   <li>Better synthesis quality than Haiku for structured knowledge extraction</li>
 *   <li>Free tier: 1,500 requests/day — enough for hundreds of uploads/day</li>
 * </ul>
 */
@Primary
@Component
public class GeminiWikiCompiler implements WikiCompilerPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiWikiCompiler.class);

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.model:gemini-2.0-flash}")
    private String model;

    @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ClaudeWikiCompiler claudeFallback;

    public GeminiWikiCompiler(
            WebClient webClient,
            ObjectMapper objectMapper,
            ClaudeWikiCompiler claudeFallback) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.claudeFallback = claudeFallback;
    }

    @Override
    public List<WikiPageDraft> compile(Avatar avatar, List<KnowledgeFile> files,
                                       List<WikiPage> existingPages) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Gemini] GEMINI_API_KEY not set — falling back to Claude Haiku");
            return claudeFallback.compile(avatar, files, existingPages);
        }

        log.info("[Gemini] Compiling wiki avatarId={} files={} model={}",
                avatar.getId(), files.size(), model);
        try {
            String prompt = buildPrompt(avatar, files, existingPages);
            String raw = callGemini(prompt);
            List<WikiPageDraft> drafts = parseResponse(raw);
            log.info("[Gemini] Compiled {} pages for avatarId={}", drafts.size(), avatar.getId());
            return drafts;
        } catch (Exception e) {
            log.warn("[Gemini] Call failed ({}), falling back to Claude Haiku: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return claudeFallback.compile(avatar, files, existingPages);
        }
    }

    private String callGemini(String prompt) {
        String url = baseUrl
                + "/v1beta/models/" + model
                + ":generateContent?key=" + apiKey;

        // Gemini REST request body
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", 8192,
                        "temperature", 0.2  // low temperature for factual extraction
                )
        );

        String responseBody = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(180));

        if (responseBody == null) {
            throw new RuntimeException("Gemini returned null response");
        }

        // Extract text from Gemini response:
        // {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode text = root
                    .path("candidates").path(0)
                    .path("content").path("parts").path(0)
                    .path("text");
            if (text.isMissingNode() || text.asText().isBlank()) {
                // Check for blocking
                JsonNode finishReason = root.path("candidates").path(0).path("finishReason");
                log.warn("[Gemini] Empty text response, finishReason={}", finishReason.asText("unknown"));
                throw new RuntimeException("Gemini returned empty text, finishReason=" + finishReason.asText());
            }
            return text.asText();
        } catch (Exception e) {
            // Log first 500 chars of raw response for debugging
            log.error("[Gemini] Failed to parse response: {}",
                    responseBody.substring(0, Math.min(500, responseBody.length())));
            throw new RuntimeException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(Avatar avatar, List<KnowledgeFile> files,
                                List<WikiPage> existingPages) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a knowledge organiser for a children's educational tutoring app (ages 8-14).

                Avatar name: %s
                Subject: %s

                ## YOUR TASK
                Convert the extracted text below into structured wiki pages.

                ## CRITICAL RULES
                1. PRESERVE ALL SPECIFIC FACTS — equations, numbers, lists, step-by-step
                   processes, experiment procedures, and definitions MUST appear in the
                   wiki page exactly as stated in the source material.
                2. Each wiki page covers ONE topic (e.g., "Photosynthesis", "Electrical Circuits").
                3. Use markdown: ## for headings, - for bullet points, **bold** for key terms.
                4. Use simple, clear language for children aged 8-14.
                5. Each page: 200-500 words — comprehensive but not overwhelming.

                ## EXAMPLE OUTPUT FORMAT
                [{"slug":"boiling-point-of-water","title":"Boiling Point of Water",
                  "content":"## What is Boiling?\\nBoiling is when water turns from **liquid** to **gas**.\\n\\n## Key Facts\\n- Water boils at **100°C** at sea level\\n- Requires **2260 kJ/kg** of heat energy",
                  "prerequisites":[]}]

                ## EXTRACTED CONTENT
                """.formatted(avatar.getName(), avatar.getSubject().name()));

        for (KnowledgeFile file : files) {
            sb.append("\n### Source: ").append(file.getFileName()).append("\n");
            String text = file.getExtractedText();
            if (text != null && !text.isBlank()) {
                sb.append(text).append("\n");
            }
        }

        if (!existingPages.isEmpty()) {
            sb.append("\n## EXISTING PAGES (merge if topics overlap)\n");
            for (WikiPage page : existingPages) {
                sb.append("- slug: ").append(page.getSlug())
                  .append(", title: ").append(page.getTitle()).append("\n");
            }
        }

        sb.append("""

                ## OUTPUT
                Reply ONLY with a JSON array — no markdown fences, no explanation:
                [{"slug":"lowercase-hyphen","title":"Title","content":"markdown","prerequisites":["slug-a"]}]
                """);

        return sb.toString();
    }

    private List<WikiPageDraft> parseResponse(String raw) {
        try {
            String json = raw.strip();
            // Strip markdown fences if Gemini wraps the JSON
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            // Find the JSON array boundaries
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }

            JsonNode array = objectMapper.readTree(json);
            List<WikiPageDraft> drafts = new ArrayList<>();
            for (JsonNode node : array) {
                String slug = node.path("slug").asText();
                String title = node.path("title").asText();
                String content = node.path("content").asText();
                List<String> prereqs = new ArrayList<>();
                JsonNode pre = node.path("prerequisites");
                if (pre.isArray()) {
                    for (JsonNode p : pre) {
                        String ps = p.asText("").trim();
                        if (!ps.isBlank()) prereqs.add(ps);
                    }
                }
                if (!slug.isBlank() && !title.isBlank()) {
                    drafts.add(new WikiPageDraft(slug, title, content, prereqs));
                }
            }
            return drafts;
        } catch (Exception e) {
            log.error("[Gemini] Failed to parse wiki pages from response", e);
            throw new RuntimeException("Failed to parse Gemini wiki compilation result: " + e.getMessage(), e);
        }
    }
}
