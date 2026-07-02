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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    // 2048 tokens ≈ 6 wiki pages at 300 tokens each — enough for any single
    // compile call. 4096 caused consistent truncation (out=4096 exactly) which
    // left the JSON array incomplete and crashed parseResponse every time.
    private static final int MAX_TOKENS = 2048;
    /// Largest single-file body we'll send Claude in one call. Anything
    /// bigger is map-reduced: split into ~CHUNK_SIZE windows, compile each,
    /// then merge drafts by slug. Was a silent truncation before B1 — long
    /// PDFs lost everything past 4k chars from the brain.
    private static final int MAX_FILE_CHARS = 4000;
    private static final int CHUNK_SIZE = 2000;
    /// Cost guard: at 2000-char chunks the same 28k-char doc fits in 15 chunks.
    /// Cap raised to 15 to keep the same effective total document coverage
    /// as the old 8 × 3500-char limit.
    private static final int MAX_CHUNKS_PER_FILE = 15;
    /// Overlap between consecutive chunks so context isn't lost at boundaries.
    private static final int CHUNK_OVERLAP = 200;

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

    // Heading patterns: markdown ## headings, numbered sections, ALL CAPS lines
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#{1,3} .+|\\d+\\.\\s+[A-Z].+|[A-Z][A-Z ]{4,})$",
            Pattern.MULTILINE);

    /**
     * Splits text at heading boundaries. Returns an empty list when no headings
     * are detected (caller falls back to {@link #chunkPlain}).
     */
    private List<String> splitByHeadings(String text) {
        Matcher m = HEADING_PATTERN.matcher(text);
        List<Integer> splits = new ArrayList<>();
        splits.add(0);
        while (m.find()) {
            if (m.start() > 0) splits.add(m.start());
        }
        splits.add(text.length());
        if (splits.size() <= 2) return List.of(); // no headings found
        List<String> sections = new ArrayList<>();
        for (int i = 0; i < splits.size() - 1; i++) {
            String section = text.substring(splits.get(i), splits.get(i + 1)).strip();
            if (!section.isBlank()) sections.add(section);
        }
        return sections;
    }

    private List<String> chunkText(String text) {
        if (text == null || text.isBlank()) return List.of();
        if (text.length() <= MAX_FILE_CHARS) return List.of(text);

        // Fix 4 — try heading-aware split first
        List<String> headingSections = splitByHeadings(text);
        if (!headingSections.isEmpty()) {
            List<String> result = new ArrayList<>();
            for (String section : headingSections) {
                if (section.length() <= CHUNK_SIZE) {
                    result.add(section);
                } else {
                    result.addAll(chunkPlain(section));
                }
                if (result.size() >= MAX_CHUNKS_PER_FILE) break;
            }
            log.info("[WikiCompiler] Heading-split produced {} chunks (sections={})",
                    result.size(), headingSections.size());
            return result;
        }

        // No headings — fall through to character-window chunking
        return chunkPlain(text);
    }

    /**
     * Character-window chunking (existing logic, extracted from the old chunkText).
     * Prefers paragraph then sentence boundaries. Applies CHUNK_OVERLAP between windows.
     */
    private List<String> chunkPlain(String text) {
        List<String> out = new ArrayList<>();
        int pos = 0;
        while (pos < text.length() && out.size() < MAX_CHUNKS_PER_FILE) {
            int end = Math.min(pos + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                // Prefer paragraph boundary in second half of chunk
                int para = text.lastIndexOf("\n\n", end);
                if (para > pos + CHUNK_SIZE / 2) {
                    end = para;
                } else {
                    // Fall back to sentence boundary (". " or ".\n")
                    int sent = text.lastIndexOf(". ", end);
                    int sentNl = text.lastIndexOf(".\n", end);
                    int sentBound = Math.max(sent, sentNl);
                    if (sentBound > pos + CHUNK_SIZE / 2) {
                        end = sentBound + 1; // include the period
                    }
                }
            }
            out.add(text.substring(pos, end));
            // 200-char overlap so context isn't lost at chunk boundaries
            pos = Math.max(pos + 1, end - CHUNK_OVERLAP);
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
            // Keep the first non-blank context summary across merged chunks.
            String context = existing.context() != null && !existing.context().isBlank()
                    ? existing.context() : d.context();
            bySlug.put(slug, new WikiPageDraft(
                    slug, existing.title(), content.toString(), prereqs, context));
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
        // A MARKING_CORPUS avatar compiles a teacher's marking standard into
        // marking-BEHAVIOUR pages; everything after the header (source loop,
        // merge block, output format) is shared so parsing + merge are identical.
        sb.append(avatar.isMarkingCorpus()
                ? markingPromptHeader(avatar)
                : avatar.isWeaknessProfile()
                    ? weaknessPromptHeader(avatar)
                    : notesPromptHeader(avatar));

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
                 "context": "1-2 sentence summary: this page covers <what> within <subject/topic>",
                 "prerequisites": ["slug-a","slug-b"]}

                For "context": a short summary that situates the page; it is
                prepended to the page when finding it later, so make it specific.
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

    /** Header for a normal study-notes avatar — topic pages (ends at the source section). */
    private String notesPromptHeader(Avatar avatar) {
        return """
                You are a knowledge organiser for a student study app used by learners aged 13-25.

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
                4. Use clear, accurate language suitable for secondary and university students (ages 13-25).
                5. If the content mentions an equation, write it out in full.
                6. If the content contains an experiment, include ALL steps.
                7. Each page should be 200-500 words — comprehensive but not overwhelming.

                ## EXAMPLE (follow this structure exactly)

                ### Example Source Text:
                Water boils at 100°C at sea level. The boiling point decreases as altitude increases.
                Boiling is when water turns from liquid to gas (water vapour). The process requires
                heat energy — specifically 2260 kJ/kg (latent heat of vaporisation).

                ### Example Output:
                [{"slug": "boiling-point-of-water",
                  "title": "Boiling Point of Water",
                  "content": "## What is Boiling?\\nBoiling is when water turns from **liquid** to **gas** (water vapour).\\n\\n## Key Facts\\n- Water boils at **100°C** at sea level\\n- The boiling point **decreases** as altitude increases\\n- Boiling requires heat energy: **2260 kJ/kg** (latent heat of vaporisation)\\n\\n## Why Does Altitude Matter?\\nAt higher altitudes, there is less air pressure pushing down on the water, so water boils at a lower temperature.",
                  "context": "Covers the boiling point of water, altitude effects and latent heat, within states of matter.",
                  "prerequisites": []}]

                ### Example Source Text 2:
                Ohm's Law: V = IR, where V is voltage (volts), I is current (amperes), R is resistance (ohms).
                A circuit with 12V battery and 4Ω resistor has current I = 12/4 = 3A.

                ### Example Output 2:
                [{"slug": "ohms-law",
                  "title": "Ohm's Law",
                  "content": "## Ohm's Law\\n**V = IR**\\n\\nWhere:\\n- **V** = Voltage (measured in Volts)\\n- **I** = Current (measured in Amperes)\\n- **R** = Resistance (measured in Ohms)\\n\\n## Example Calculation\\nA circuit with a **12V** battery and **4Ω** resistor:\\n- I = V ÷ R = 12 ÷ 4 = **3A**\\n\\n## Key Points\\n- Higher resistance → lower current (for same voltage)\\n- Higher voltage → higher current (for same resistance)",
                  "context": "Explains Ohm's Law (V=IR) and a worked current calculation, within electricity/circuits.",
                  "prerequisites": []}]

                Now apply the same approach to the actual content below:
                ## EXTRACTED CONTENT TO COMPILE

                """.formatted(avatar.getName(), avatar.getSubject().name());
    }

    /**
     * Header for a MARKING_CORPUS avatar — compiles a teacher's rubrics, mark
     * schemes, guidelines and PAST MARKED papers into reusable marking-BEHAVIOUR
     * pages so the AI drafts feedback in THIS centre's marking style.
     */
    // Marking prompt lives in ONE place (WikiCompilerPrompts) so it can't drift
    // between the Gemini (primary) compiler and this fallback.
    private String markingPromptHeader(Avatar avatar) {
        return WikiCompilerPrompts.markingHeader(avatar.getSubject().label());
    }

    // Weakness-profile prompt also lives in WikiCompilerPrompts so it can't drift.
    private String weaknessPromptHeader(Avatar avatar) {
        return WikiCompilerPrompts.weaknessHeader(avatar.getSubject().label());
    }

    private List<WikiPageDraft> parseResponse(String raw) {
        String json = raw.strip();
        // Strip markdown fences
        if (json.startsWith("```")) {
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
        }
        // Find the JSON array boundaries
        int arrayStart = json.indexOf('[');
        if (arrayStart < 0) {
            log.error("[WikiCompiler] No JSON array found in response ({}chars)", raw.length());
            throw new RuntimeException("No JSON array in wiki compilation response");
        }
        json = json.substring(arrayStart);

        // Primary parse: try the full JSON array
        try {
            return extractDrafts(objectMapper.readTree(json));
        } catch (Exception e) {
            // Truncation recovery: the response was cut at maxTokens (out==maxTokens).
            // Scan backwards for the last complete object boundary so we can
            // still return the pages that were fully generated.
            log.warn("[WikiCompiler] Full JSON parse failed (likely truncated at {} chars): {} — attempting partial recovery",
                    raw.length(), e.getMessage());
            return recoverPartialDrafts(json);
        }
    }

    private List<WikiPageDraft> extractDrafts(JsonNode array) {
        List<WikiPageDraft> drafts = new ArrayList<>();
        for (JsonNode node : array) {
            String slug = node.path("slug").asText();
            String title = node.path("title").asText();
            String content = node.path("content").asText();
            String context = node.path("context").asText(null);
            List<String> prereqs = new ArrayList<>();
            JsonNode pre = node.path("prerequisites");
            if (pre.isArray()) {
                for (JsonNode p : pre) {
                    String ps = p.asText("").trim();
                    if (!ps.isBlank()) prereqs.add(ps);
                }
            }
            if (!slug.isBlank() && !title.isBlank()) {
                drafts.add(new WikiPageDraft(slug, title, content, prereqs, context));
            }
        }
        log.debug("[WikiCompiler] Parsed {} wiki page drafts", drafts.size());
        return drafts;
    }

    /**
     * Recovers as many complete page objects as possible from a truncated JSON array.
     * Scans backward from the end of the string looking for a closing brace that
     * marks the end of a complete object, then closes the array and parses what it can.
     */
    private List<WikiPageDraft> recoverPartialDrafts(String truncatedJson) {
        // Walk backward looking for "}," or "}" that closes the last complete object
        String attempt = truncatedJson;
        for (int i = attempt.length() - 1; i > 0; i--) {
            char c = attempt.charAt(i);
            if (c == '}') {
                String candidate = attempt.substring(0, i + 1) + "]";
                try {
                    JsonNode array = objectMapper.readTree(candidate);
                    List<WikiPageDraft> drafts = extractDrafts(array);
                    if (!drafts.isEmpty()) {
                        log.info("[WikiCompiler] Partial recovery: salvaged {} pages from truncated JSON",
                                drafts.size());
                        return drafts;
                    }
                } catch (Exception ignored) {
                    // Keep scanning backward
                }
            }
        }
        log.error("[WikiCompiler] Partial recovery failed — no complete objects found in truncated response");
        throw new RuntimeException("Failed to parse wiki compilation result from Claude (truncated + no recovery)");
    }
}
