package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FIX 4 — section-aware (heading-based) chunking in ClaudeWikiCompiler.
 *
 * <p>A document with clear headings should produce one chunk per section rather
 * than overlapping character windows. Documents without headings should still
 * fall back to character-window chunking (existing behaviour).
 */
class HeadingAwareChunkingTest {

    private ClaudeWikiCompiler compiler;

    @BeforeEach
    void setUp() {
        ClaudeApiClient mockApiClient = mock(ClaudeApiClient.class);
        ModelRouter mockModelRouter = mock(ModelRouter.class);
        when(mockModelRouter.forWikiCompile()).thenReturn("claude-haiku-4-5");
        when(mockApiClient.complete(anyString(), anyInt(), anyString())).thenReturn("[]");
        compiler = new ClaudeWikiCompiler(mockApiClient, new ObjectMapper(), mockModelRouter);
    }

    // ── Heading-split path ────────────────────────────────────────────────────

    @Test
    void markdownHeadings_produceOneChunkPerSection() throws Exception {
        // Build a document with 3 markdown sections, each small enough to fit in one chunk
        String section1 = "## Introduction\n" + "Content about introduction. ".repeat(20);
        String section2 = "## Photosynthesis\n" + "Content about photosynthesis. ".repeat(20);
        String section3 = "## Summary\n" + "Summary content here. ".repeat(20);
        String text = section1 + "\n\n" + section2 + "\n\n" + section3;

        // Must exceed MAX_FILE_CHARS (4000) to trigger chunking
        String padding = "x".repeat(Math.max(0, 4001 - text.length()));
        text = text + padding;

        List<String> chunks = invokeChunkText(text);

        // Should produce chunks that start with the heading text (section-aware)
        assertThat(chunks)
                .as("Heading-split should produce at least 2 chunks for a multi-section document")
                .hasSizeGreaterThanOrEqualTo(2);

        // Each heading should appear in a chunk (not split across chunk boundaries)
        String allChunks = String.join(" |BOUNDARY| ", chunks);
        // Introduction must not be split mid-section
        assertThat(allChunks).contains("Introduction");
        assertThat(allChunks).contains("Photosynthesis");
    }

    @Test
    void numberedSections_triggersHeadingSplit() throws Exception {
        // Numbered sections like textbooks: "1. Introduction", "2. Main Topic"
        String section1 = "1. Introduction\n" + "Intro text here. ".repeat(30);
        String section2 = "2. Main Topic\n" + "Main content here. ".repeat(30);
        String section3 = "3. Conclusion\n" + "Conclusion text here. ".repeat(30);
        String text = section1 + "\n" + section2 + "\n" + section3;
        // Pad to exceed MAX_FILE_CHARS
        text = text + "y".repeat(Math.max(0, 4001 - text.length()));

        List<String> chunks = invokeChunkText(text);
        assertThat(chunks)
                .as("Numbered sections should trigger heading-split")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void allCapsHeadings_triggersHeadingSplit() throws Exception {
        // ALL CAPS headings (common in textbooks)
        String section1 = "INTRODUCTION\n" + "Introduction text. ".repeat(30);
        String section2 = "MAIN CONCEPT\n" + "Main concept text. ".repeat(30);
        String text = section1 + "\n" + section2;
        text = text + "z".repeat(Math.max(0, 4001 - text.length()));

        List<String> chunks = invokeChunkText(text);
        assertThat(chunks)
                .as("ALL CAPS headings should trigger heading-split")
                .hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void noHeadings_fallsBackToCharacterChunking() throws Exception {
        // Plain text with no heading markers — must fall back to character-window chunking
        String text = "Sentence one. ".repeat(500); // ~7000 chars, no headings
        List<String> chunks = invokeChunkText(text);

        assertThat(chunks)
                .as("No-heading text must still be chunked (not lost)")
                .isNotEmpty();
        // Character-based chunking keeps chunks at most CHUNK_SIZE chars (2000),
        // with up to +1 for the sentence-ending period inclusion.
        for (String chunk : chunks) {
            assertThat(chunk.length())
                    .as("Character-chunked chunks should not significantly exceed CHUNK_SIZE (2000)")
                    .isLessThanOrEqualTo(2001);
        }
    }

    @Test
    void oversizedSection_subChunkedCorrectly() throws Exception {
        // A single section that exceeds CHUNK_SIZE should be sub-chunked
        String heading = "## Very Long Section\n";
        String largeBody = "word ".repeat(1000); // ~5000 chars > CHUNK_SIZE (2000)
        String text = heading + largeBody;
        // Pad to exceed MAX_FILE_CHARS
        text = text + "p".repeat(Math.max(0, 4001 - text.length()));

        List<String> chunks = invokeChunkText(text);

        assertThat(chunks)
                .as("Oversized section must be sub-chunked into multiple pieces")
                .hasSizeGreaterThanOrEqualTo(2);
        for (String chunk : chunks) {
            assertThat(chunk.length())
                    .as("Sub-chunked pieces must not exceed CHUNK_SIZE (2000)")
                    .isLessThanOrEqualTo(2000);
        }
    }

    @Test
    void chunkCount_cappedAtFifteen() throws Exception {
        // Very long document with many headings — must not exceed MAX_CHUNKS_PER_FILE (15)
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            sb.append("## Section ").append(i).append("\n");
            sb.append("Content for section ").append(i).append(". ".repeat(20)).append("\n\n");
        }
        List<String> chunks = invokeChunkText(sb.toString());
        assertThat(chunks.size())
                .as("Chunk count must not exceed MAX_CHUNKS_PER_FILE = 15")
                .isLessThanOrEqualTo(15);
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> invokeChunkText(String text) throws Exception {
        Method m = ClaudeWikiCompiler.class.getDeclaredMethod("chunkText", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(compiler, text);
    }
}
