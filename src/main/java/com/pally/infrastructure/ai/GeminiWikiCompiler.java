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

    @jakarta.annotation.PostConstruct
    void logConfig() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Gemini] ⚠️  GEMINI_API_KEY is NOT SET — wiki compile will use Claude Haiku fallback");
        } else {
            log.info("[Gemini] ✅ Wiki compiler ACTIVE model={} key={}…{}",
                    model, apiKey.substring(0, Math.min(8, apiKey.length())),
                    apiKey.length() > 8 ? apiKey.substring(apiKey.length() - 4) : "");
        }
    }

    @Override
    public List<WikiPageDraft> compile(Avatar avatar, List<KnowledgeFile> files,
                                       List<WikiPage> existingPages) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Gemini] GEMINI_API_KEY not set — falling back to Claude Haiku");
            return claudeFallback.compile(avatar, files, existingPages);
        }

        int totalChars = files.stream()
                .mapToInt(f -> f.getExtractedText() != null ? f.getExtractedText().length() : 0)
                .sum();
        log.info("[Gemini] ──► COMPILE START avatarId={} files={} totalChars={} model={}",
                avatar.getId(), files.size(), totalChars, model);
        files.forEach(f -> log.info("[Gemini]   file: {} status={} chars={}",
                f.getFileName(), f.getStatus(),
                f.getExtractedText() != null ? f.getExtractedText().length() : 0));

        long start = System.currentTimeMillis();
        try {
            // For very large documents (> 30k chars ≈ 100 pages), split into chunks
            // and merge results. This prevents output-token truncation which silently
            // drops chapters when 16384 tokens isn't enough for all pages.
            // Threshold: 30k chars / ~4 chars per token ≈ 7500 input tokens per chunk.
            List<WikiPageDraft> drafts;
            if (totalChars > 30_000) {
                drafts = compileChunked(avatar, files, existingPages);
            } else {
                String prompt = buildPrompt(avatar, files, existingPages);
                log.debug("[Gemini] Prompt length: {} chars (~{} tokens)", prompt.length(), prompt.length() / 4);
                String raw = callGemini(prompt);
                drafts = parseResponse(raw);
            }

            long ms = System.currentTimeMillis() - start;
            log.info("[Gemini] ◄── COMPILE DONE avatarId={} pages={} latency={}ms",
                    avatar.getId(), drafts.size(), ms);
            drafts.forEach(d -> log.info("[Gemini]   page: slug={} title={} contentLen={} prereqs={}",
                    d.slug(), d.title(), d.content().length(), d.prerequisites()));
            if (drafts.isEmpty()) {
                log.warn("[Gemini] ⚠️  0 pages produced for avatarId={}. totalChars={}",
                        avatar.getId(), totalChars);
            }
            return drafts;
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - start;
            log.warn("[Gemini] ✗ COMPILE FAILED after {}ms ({}): {} — falling back to Claude Haiku",
                    ms, e.getClass().getSimpleName(), e.getMessage());
            return claudeFallback.compile(avatar, files, existingPages);
        }
    }

    // Max chars per Gemini chunk: ~25k chars ≈ 6k input tokens, leaves plenty
    // of room in the 16k output-token ceiling for ~60 wiki pages per call.
    private static final int GEMINI_CHUNK_CHARS = 25_000;

    /**
     * Splits the file corpus into 25k-char chunks, compiles each separately,
     * then deduplicates and merges results by slug. Used for documents > 30k chars
     * where a single Gemini call would hit the output-token ceiling.
     */
    private List<WikiPageDraft> compileChunked(Avatar avatar, List<KnowledgeFile> files,
                                                List<WikiPage> existingPages) {
        // Concatenate all extracted text and split into chunks
        String allText = files.stream()
                .filter(f -> f.getExtractedText() != null)
                .map(f -> "## Source: " + f.getFileName() + "\n" + f.getExtractedText())
                .collect(java.util.stream.Collectors.joining("\n\n"));

        List<String> chunks = splitIntoChunks(allText, GEMINI_CHUNK_CHARS);
        log.info("[Gemini] Chunked compile: {} chunks for {} chars, avatarId={}",
                chunks.size(), allText.length(), avatar.getId());

        // Compile each chunk, accumulate pages by slug (later chunk wins on conflict)
        java.util.LinkedHashMap<String, WikiPageDraft> bySlug = new java.util.LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                // Build a mini-prompt for this chunk only
                String prompt = buildChunkPrompt(avatar, chunk, i + 1, chunks.size(), existingPages);
                String raw = callGemini(prompt);
                List<WikiPageDraft> chunkDrafts = parseResponse(raw);
                log.info("[Gemini] Chunk {}/{}: {} pages produced", i + 1, chunks.size(), chunkDrafts.size());
                for (WikiPageDraft d : chunkDrafts) {
                    WikiPageDraft existing = bySlug.get(d.slug());
                    if (existing == null || d.content().length() > existing.content().length()) {
                        bySlug.put(d.slug(), d);
                    }
                }
            } catch (Exception e) {
                log.warn("[Gemini] Chunk {}/{} failed: {}", i + 1, chunks.size(), e.getMessage());
            }
        }
        return new java.util.ArrayList<>(bySlug.values());
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new java.util.ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            // Prefer splitting at a section boundary (## or blank line)
            if (end < text.length()) {
                int sectionBound = text.lastIndexOf("\n## ", end);
                int paraBound = text.lastIndexOf("\n\n", end);
                int split = sectionBound > pos + chunkSize / 2 ? sectionBound
                        : paraBound > pos + chunkSize / 2 ? paraBound
                        : end;
                end = split;
            }
            chunks.add(text.substring(pos, end).strip());
            pos = end;
        }
        return chunks;
    }

    private String buildChunkPrompt(Avatar avatar, String chunkText, int chunkNum, int totalChunks,
                                     List<WikiPage> existingPages) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a knowledge organiser for a children's educational tutoring app (ages 8-14).
                Avatar: %s | Subject: %s

                ## TASK
                Convert the extracted text below into structured wiki pages.
                This is chunk %d of %d from a larger document.

                ## RULES
                1. PRESERVE ALL SPECIFIC FACTS — equations, numbers, formulas EXACTLY as stated.
                2. One topic per page. Use markdown: ## headings, - bullets, **bold** for key terms.
                3. Pages: 200-500 words each.
                4. Reply ONLY with a JSON array — no fences:
                [{"slug":"lowercase-hyphen","title":"Title","content":"markdown","prerequisites":["slug-a"]}]

                ## CONTENT (chunk %d of %d):
                %s
                """.formatted(avatar.getName(), avatar.getSubject().name(), chunkNum, totalChunks,
                chunkNum, totalChunks, chunkText));
        return sb.toString();
    }

    private String callGemini(String prompt) {
        String url = baseUrl
                + "/v1beta/models/" + model
                + ":generateContent?key=" + apiKey;

        // Gemini REST request body
        // maxOutputTokens raised from 8192 → 16384: stress testing showed that
        // 8192 was silently truncating output mid-JSON-array for large documents
        // (e.g. 22-page PDF with 6 chapters → only Chapter 1 pages produced).
        // Gemini 2.0 Flash supports up to 8192 output tokens by default but up
        // to 65536 with the extended flag; 16384 safely covers ~80 wiki pages.
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", 16384,
                        "temperature", 0.2
                )
        );

        log.debug("[Gemini] POST {} (promptChars={})", url.replaceAll("key=.*", "key=[REDACTED]"), prompt.length());
        long callStart = System.currentTimeMillis();

        String responseBody = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(180));

        log.debug("[Gemini] HTTP response received in {}ms, bodyLen={}",
                System.currentTimeMillis() - callStart,
                responseBody != null ? responseBody.length() : 0);

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
                JsonNode finishReason = root.path("candidates").path(0).path("finishReason");
                JsonNode promptFeedback = root.path("promptFeedback");
                log.warn("[Gemini] Empty text response. finishReason={} promptFeedback={}",
                        finishReason.asText("unknown"), promptFeedback);
                log.warn("[Gemini] Full response (first 800 chars): {}",
                        responseBody.substring(0, Math.min(800, responseBody.length())));
                throw new RuntimeException("Gemini returned empty text, finishReason=" + finishReason.asText());
            }
            String textStr = text.asText();
            log.debug("[Gemini] Extracted text length={} preview={}",
                    textStr.length(), textStr.substring(0, Math.min(200, textStr.length())));
            return textStr;
        } catch (Exception e) {
            log.error("[Gemini] Failed to parse response. First 500 chars: {}",
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
