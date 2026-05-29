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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /// Largest single-file body we'll send Claude in one call. Anything
    /// bigger is map-reduced: split into ~CHUNK_SIZE windows, compile each,
    /// then merge drafts by slug. Was a silent truncation before B1 — long
    /// PDFs lost everything past 4k chars from the brain.
    private static final int MAX_FILE_CHARS = 4000;
    private static final int CHUNK_SIZE = 3500;
    /// Cost guard: a 100-page textbook compiled as 25 chunks of 3.5k chars
    /// each is ~12.5k tokens to Haiku. Cap at 8 chunks/file (~28k chars)
    /// and ask the user to split the file if they exceed it.
    private static final int MAX_CHUNKS_PER_FILE = 8;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;

    @Override
    public List<WikiPageDraft> compile(Avatar avatar, List<KnowledgeFile> files, List<WikiPage> existingPages) {
        log.debug("Compiling wiki for avatarId={} fileCount={}",
                avatar.getId(), files.size());

        // Fast path: nothing is oversized → one batched Claude call (preserves
        // the prior cross-file organisation behaviour for the common case).
        if (files.stream().allMatch(f -> length(f.getExtractedText()) <= MAX_FILE_CHARS)) {
            String prompt = buildPrompt(avatar, files, existingPages, null);
            String raw = apiClient.complete(
                    modelRouter.forWikiCompile(), MAX_TOKENS, prompt);
            return parseResponse(raw);
        }

        // Map-reduce path: chunk any oversized file into windows, run one
        // Claude call per chunk, then merge drafts by slug. Cost stays
        // bounded by MAX_CHUNKS_PER_FILE per source file.
        Map<String, WikiPageDraft> bySlug = new LinkedHashMap<>();
        for (KnowledgeFile file : files) {
            List<String> chunks = chunkText(file.getExtractedText());
            for (int i = 0; i < chunks.size(); i++) {
                String chunkLabel = "chunk %d of %d".formatted(
                        i + 1, chunks.size());
                String prompt = buildPrompt(avatar, List.of(file),
                        existingPages, new ChunkContext(chunks.get(i), chunkLabel));
                String raw;
                try {
                    raw = apiClient.complete(
                            modelRouter.forWikiCompile(), MAX_TOKENS, prompt);
                } catch (Exception e) {
                    log.warn("[WikiCompiler] chunk {} failed for {}: {}",
                            chunkLabel, file.getFileName(), e.getMessage());
                    continue;
                }
                mergeDrafts(bySlug, parseResponse(raw));
            }
        }
        return new ArrayList<>(bySlug.values());
    }

    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= MAX_FILE_CHARS) return List.of(text);
        List<String> out = new ArrayList<>();
        int pos = 0;
        while (pos < text.length() && out.size() < MAX_CHUNKS_PER_FILE) {
            int end = Math.min(pos + CHUNK_SIZE, text.length());
            // Try to break on a paragraph boundary so equations / list items
            // aren't sliced in half across two Claude calls.
            if (end < text.length()) {
                int para = text.lastIndexOf("\n\n", end);
                if (para > pos + (CHUNK_SIZE / 2)) end = para;
            }
            out.add(text.substring(pos, end));
            pos = end;
        }
        if (pos < text.length()) {
            log.warn("[WikiCompiler] source exceeded {}-chunk cap; tail truncated",
                    MAX_CHUNKS_PER_FILE);
        }
        return out;
    }

    /// Merge two slug-keyed sets: same slug → concatenate content with a
    /// separator so later passes pick up additions; prerequisite slugs
    /// union. Title from the first occurrence wins (chunks usually agree).
    private void mergeDrafts(Map<String, WikiPageDraft> bySlug,
                              List<WikiPageDraft> incoming) {
        for (WikiPageDraft d : incoming) {
            String slug = d.slug();
            WikiPageDraft existing = bySlug.get(slug);
            if (existing == null) {
                bySlug.put(slug, d);
                continue;
            }
            StringBuilder content = new StringBuilder(existing.content());
            if (!existing.content().isBlank() && !d.content().isBlank()) {
                content.append("\n\n");
            }
            content.append(d.content());
            List<String> prereqs = new ArrayList<>(existing.prerequisites());
            for (String p : d.prerequisites()) {
                if (!prereqs.contains(p)) prereqs.add(p);
            }
            bySlug.put(slug, new WikiPageDraft(
                    slug, existing.title(), content.toString(), prereqs));
        }
    }

    private int length(String s) {
        return s == null ? 0 : s.length();
    }

    /// Marker that the prompt should treat its single file as a slice of a
    /// larger source. {@code text} replaces the file's full extractedText
    /// for this call only.
    private record ChunkContext(String text, String label) {}

    /**
     * Legacy method for backward compatibility — delegates to {@link #compile(Avatar, List, List)}.
     */
    public List<WikiPageDraft> compile_legacy(Avatar avatar, List<KnowledgeFile> readyFiles, List<WikiPage> existingPages) {
        return compile(avatar, readyFiles, existingPages);
    }

    private String buildPrompt(Avatar avatar, List<KnowledgeFile> files,
                               List<WikiPage> existingPages,
                               ChunkContext chunkContext) {
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
            sb.append("### Source: ").append(file.getFileName());
            if (chunkContext != null) {
                sb.append(" (").append(chunkContext.label()).append(")");
            }
            sb.append("\n");
            // Chunk-mode wins when present so a single oversized file
            // doesn't fall back to the legacy 4000-char truncation.
            String text = chunkContext != null
                    ? chunkContext.text()
                    : file.getExtractedText();
            if (text != null && !text.isBlank()) {
                sb.append(text).append("\n\n");
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
                Each object:
                {"slug": "lowercase-hyphenated", "title": "Human Title",
                 "content": "full markdown content",
                 "prerequisites": ["slug-a","slug-b"]}

                For "prerequisites": list the slugs of OTHER pages in this same
                set (or existing pages listed above) that a student must
                understand FIRST to grasp this page. Use the exact slug strings.
                If none, use an empty array [].

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
            log.debug("Parsed {} wiki page drafts from Claude response", drafts.size());
            return drafts;
        } catch (Exception e) {
            log.error("Failed to parse wiki compile response", e);
            throw new RuntimeException("Failed to parse wiki compilation result from Claude", e);
        }
    }
}
