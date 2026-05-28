package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Claude-backed implementation of {@link WikiCompilerPort}.
 *
 * <p>Takes an avatar and a list of knowledge files and asks Claude to organise
 * the content into structured markdown wiki pages returned as a JSON array.
 */
@Component
@RequiredArgsConstructor
public class ClaudeWikiCompiler implements WikiCompilerPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudeWikiCompiler.class);
    private static final int MAX_TOKENS = 4096;
    private static final int MAX_FILE_CHARS = 4000;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;

    @Override
    public List<WikiPageDraft> compile(Avatar avatar, List<KnowledgeFile> files, List<WikiPage> existingPages) {
        String prompt = buildPrompt(avatar, files, existingPages);
        log.debug("Compiling wiki for avatarId={} fileCount={}", avatar.getId(), files.size());

        String raw = apiClient.complete(modelRouter.forWikiCompile(), MAX_TOKENS, prompt);
        return parseResponse(raw);
    }

    /**
     * Legacy method for backward compatibility — delegates to {@link #compile(Avatar, List, List)}.
     */
    public List<WikiPageDraft> compile_legacy(Avatar avatar, List<KnowledgeFile> readyFiles, List<WikiPage> existingPages) {
        return compile(avatar, readyFiles, existingPages);
    }

    private String buildPrompt(Avatar avatar, List<KnowledgeFile> files, List<WikiPage> existingPages) {
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
                   wiki page exactly as stated in the source material. Do NOT generalise
                   or paraphrase specific facts.
                2. Each wiki page covers ONE topic (e.g., "Photosynthesis",
                   "Electrical Circuits").
                3. Use markdown formatting: ## for headings, - for bullet points,
                   **bold** for key terms.
                4. Use simple, clear language suitable for children aged 8-14.
                5. If the content mentions an equation, write it out in full.
                6. If the content contains an experiment, include ALL steps.
                7. Each page should be 200-500 words — comprehensive but not overwhelming.

                """.formatted(avatar.getName(), avatar.getSubject().name()));

        sb.append("## EXTRACTED CONTENT TO COMPILE\n\n");
        for (KnowledgeFile file : files) {
            sb.append("### Source: ").append(file.getFileName()).append("\n");
            String text = file.getExtractedText();
            if (text != null && !text.isBlank()) {
                String truncated = text.length() > MAX_FILE_CHARS
                        ? text.substring(0, MAX_FILE_CHARS) + "\n[... truncated ...]"
                        : text;
                sb.append(truncated).append("\n\n");
            } else {
                sb.append("(no text content available)\n\n");
            }
        }

        if (!existingPages.isEmpty()) {
            sb.append("\n## EXISTING WIKI PAGES (merge new content into these if topics overlap)\n\n");
            for (WikiPage page : existingPages) {
                String preview = page.getContent().length() > 500
                        ? page.getContent().substring(0, 500) + "..."
                        : page.getContent();
                sb.append("### ").append(page.getTitle())
                  .append(" (slug: ").append(page.getSlug()).append(")\n");
                sb.append(preview).append("\n\n");
            }
        }

        sb.append("""

                ## OUTPUT FORMAT
                Respond ONLY with a JSON array — no markdown fences, no extra text.
                Each object: {"slug": "lowercase-hyphenated", "title": "Human Title", "content": "full markdown content"}

                IMPORTANT: The "content" field must contain ALL the specific facts,
                equations, and details from the source material. Do not summarise —
                preserve the information.
                """);

        return sb.toString();
    }

    private List<WikiPageDraft> parseResponse(String raw) {
        try {
            String json = raw.strip();
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            JsonNode array = objectMapper.readTree(json);
            List<WikiPageDraft> drafts = new ArrayList<>();
            for (JsonNode node : array) {
                String slug = node.path("slug").asText();
                String title = node.path("title").asText();
                String content = node.path("content").asText();
                if (!slug.isBlank() && !title.isBlank()) {
                    drafts.add(new WikiPageDraft(slug, title, content));
                }
            }
            log.debug("Parsed {} wiki page drafts from Claude response", drafts.size());
            return drafts;
        } catch (Exception e) {
            log.error("Failed to parse wiki compile response", e);
            throw new RuntimeException("Failed to parse wiki compilation result from Claude", e);
        }
    }
}
