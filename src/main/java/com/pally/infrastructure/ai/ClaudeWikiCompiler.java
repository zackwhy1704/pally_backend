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
import org.springframework.beans.factory.annotation.Value;
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
    private static final int MAX_FILE_CHARS = 2000;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Value("${claude.api.model}")
    private String model;

    @Override
    public List<WikiPageDraft> compile(Avatar avatar, List<KnowledgeFile> files, List<WikiPage> existingPages) {
        String prompt = buildPrompt(avatar, files, existingPages);
        log.debug("Compiling wiki for avatarId={} fileCount={}", avatar.getId(), files.size());

        String raw = apiClient.complete(model, MAX_TOKENS, prompt);
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
                You are a knowledge organiser for a children's educational tutoring app.

                Avatar name: %s
                Subject: %s

                Compile the following extracted text into structured wiki pages suitable for a child-friendly tutor.
                Each page should cover a single topic. Use simple, clear language.

                """.formatted(avatar.getName(), avatar.getSubject().name()));

        sb.append("## Extracted Content\n\n");
        for (KnowledgeFile file : files) {
            sb.append("### File: ").append(file.getFileName()).append("\n");
            sb.append("(content extracted from file ID: ").append(file.getId()).append(")\n\n");
        }

        if (!existingPages.isEmpty()) {
            sb.append("\n## Existing Wiki Pages (for context / merging)\n\n");
            for (WikiPage page : existingPages) {
                String preview = page.getContent().length() > 300
                        ? page.getContent().substring(0, 300) + "..."
                        : page.getContent();
                sb.append("### ").append(page.getTitle()).append(" (slug: ").append(page.getSlug()).append(")\n");
                sb.append(preview).append("\n\n");
            }
        }

        sb.append("""

                Respond ONLY with a JSON array of wiki page objects — no markdown fences, no extra text.
                Each object must have exactly these fields:
                  "slug": a lowercase hyphenated URL-safe identifier (e.g. "photosynthesis")
                  "title": a human-readable page title
                  "content": full markdown content for the page

                Example format:
                [{"slug":"topic-one","title":"Topic One","content":"## Topic One\\n..."},...]
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
