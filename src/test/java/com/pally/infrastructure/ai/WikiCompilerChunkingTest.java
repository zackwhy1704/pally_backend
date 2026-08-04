package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.KnowledgeFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for FIX 1 (few-shot examples in prompt) and FIX 4 (smaller chunks + overlap).
 */
class WikiCompilerChunkingTest {

    private ClaudeWikiCompiler compiler;
    private ClaudeApiClient mockApiClient;
    private ModelRouter mockModelRouter;

    @BeforeEach
    void setUp() {
        mockApiClient = mock(ClaudeApiClient.class);
        mockModelRouter = mock(ModelRouter.class);
        when(mockModelRouter.forWikiCompile()).thenReturn("claude-haiku-4-5");
        // ClaudeWikiCompiler calls the 3-arg complete(model, maxTokens, prompt)
        when(mockApiClient.complete(anyString(), anyInt(), anyString())).thenReturn("[]");
        compiler = new ClaudeWikiCompiler(mockApiClient, new ObjectMapper(), mockModelRouter);
    }

    // ── FIX 1 — Few-shot examples appear in the prompt ───────────────────────

    @Test
    void buildPrompt_containsFewShotExampleStructure() {
        // Root-cause fix (mirrors GeminiWikiCompiler, fix/zh-compile-mixed-language): the
        // old example carried concrete English prose ("Boiling Point of Water", "Ohm's
        // Law") strong enough to outweigh the trailing zh directive. The example is now
        // structure/placeholder-only — this asserts the STRUCTURE survives (the section
        // still exists, still precedes the extracted content, still ends with the "apply
        // the same approach" instruction), not the specific old wording.
        Avatar avatar = mockAvatar("Zap", "SCIENCE");
        KnowledgeFile file = mockFile("test.txt", "Some content about photosynthesis.");

        compiler.compile(avatar, List.of(file), List.of());

        // Capture the prompt passed to Claude (3-arg complete)
        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockApiClient).complete(anyString(), anyInt(), promptCaptor.capture());

        String prompt = promptCaptor.getValue();
        assertThat(prompt)
                .as("Prompt should still carry the EXAMPLE section header")
                .contains("## EXAMPLE (follow this JSON structure exactly");
        assertThat(prompt)
                .as("Prompt should still carry an Example Output block")
                .contains("### Example Output:");
        assertThat(prompt)
                .as("Prompt should contain the 'Now apply the same approach' instruction")
                .contains("Now apply the same approach to the actual content below");
        assertThat(prompt)
                .as("The old worked example's concrete English sentences must be gone")
                .doesNotContain("Boiling Point of Water")
                .doesNotContain("Ohm's Law")
                .doesNotContain("Boiling is when water turns from");
    }

    @Test
    void buildPrompt_fewShotExamples_appearBeforeExtractedContent() {
        Avatar avatar = mockAvatar("Zap", "SCIENCE");
        KnowledgeFile file = mockFile("test.txt", "Real content here.");

        compiler.compile(avatar, List.of(file), List.of());

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockApiClient).complete(anyString(), anyInt(), promptCaptor.capture());

        String prompt = promptCaptor.getValue();
        int examplePos = prompt.indexOf("### Example Output:");
        int extractedPos = prompt.indexOf("## EXTRACTED CONTENT TO COMPILE");
        assertThat(examplePos).isGreaterThan(0);
        assertThat(extractedPos).isGreaterThan(0);
        assertThat(examplePos)
                .as("Few-shot example must appear BEFORE the extracted content section")
                .isLessThan(extractedPos);
    }

    @Test
    void buildPrompt_criticalRulesAppearsBeforeFewShot() {
        Avatar avatar = mockAvatar("Zap", "SCIENCE");
        KnowledgeFile file = mockFile("test.txt", "content");

        compiler.compile(avatar, List.of(file), List.of());

        var promptCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mockApiClient).complete(anyString(), anyInt(), promptCaptor.capture());

        String prompt = promptCaptor.getValue();
        int rulesPos = prompt.indexOf("CRITICAL RULES");
        int examplePos = prompt.indexOf("EXAMPLE");
        assertThat(rulesPos).isGreaterThan(0);
        assertThat(examplePos).isGreaterThan(0);
        assertThat(rulesPos)
                .as("CRITICAL RULES must appear before the EXAMPLE section")
                .isLessThan(examplePos);
    }

    // ── FIX 4 — Chunk size reduced to 2000, overlap applied ──────────────────

    @Test
    void chunkText_shortText_returnsSingleChunk() throws Exception {
        String short500 = "a".repeat(500);
        List<String> chunks = invokeChunkText(short500);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(short500);
    }

    @Test
    void chunkText_textJustUnderThreshold_returnsSingleChunk() throws Exception {
        // MAX_FILE_CHARS = 4000
        String text = "x".repeat(4000);
        List<String> chunks = invokeChunkText(text);
        assertThat(chunks).hasSize(1);
    }

    @Test
    void chunkText_longText_chunksAreAtMost2000Chars() throws Exception {
        // CHUNK_SIZE = 2000 — chunks must not exceed this
        String text = "word ".repeat(1000); // 5000 chars
        List<String> chunks = invokeChunkText(text);
        assertThat(chunks).isNotEmpty();
        for (String chunk : chunks) {
            assertThat(chunk.length())
                    .as("No chunk should exceed CHUNK_SIZE (2000)")
                    .isLessThanOrEqualTo(2000);
        }
    }

    @Test
    void chunkText_overlapMeansConsecutiveChunksShareContent() throws Exception {
        // Build a text of uniform repeated chars so overlap is easy to verify
        String text = "A".repeat(4500); // big enough for at least 2 chunks (4500 > 4000 = MAX_FILE_CHARS)
        List<String> chunks = invokeChunkText(text);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        // The end of chunk 1 (last 200 chars) should appear at the start of chunk 2
        String endOfChunk1 = chunks.get(0).substring(
                Math.max(0, chunks.get(0).length() - 200));
        assertThat(chunks.get(1))
                .as("Chunk 2 should start with the last 200 chars of Chunk 1 (overlap)")
                .startsWith(endOfChunk1.substring(0, Math.min(50, endOfChunk1.length())));
    }

    @Test
    void chunkText_prefersParagraphBoundary() throws Exception {
        // Two clear paragraphs separated by \n\n
        // Must exceed MAX_FILE_CHARS (4000) to trigger chunking
        String para1 = "First paragraph. ".repeat(150);  // ~2550 chars
        String para2 = "Second paragraph. ".repeat(200); // ~3600 chars
        String text = para1 + "\n\n" + para2;            // ~6150 chars > 4000

        List<String> chunks = invokeChunkText(text);
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);

        // First chunk should end with content from para1 (before \n\n)
        assertThat(chunks.get(0).trim())
                .as("First chunk should end at the paragraph break")
                .endsWith("First paragraph.");
    }

    @Test
    void chunkText_fallsBackToSentenceBoundary_whenNoParagraphBreak() throws Exception {
        // No double-newline, but clear sentence endings
        String text = "Sentence one. ".repeat(200); // ~2800 chars, no \n\n
        List<String> chunks = invokeChunkText(text);
        assertThat(chunks).isNotEmpty();
        // First chunk should end at a sentence boundary
        assertThat(chunks.get(0).trim())
                .as("Chunk should end at sentence boundary when no paragraph break found")
                .endsWith("Sentence one.");
    }

    @Test
    void chunkText_maxChunksCapAtFifteen() throws Exception {
        // MAX_CHUNKS_PER_FILE = 15
        String text = "word ".repeat(100000); // very long text
        List<String> chunks = invokeChunkText(text);
        assertThat(chunks.size())
                .as("Should not exceed MAX_CHUNKS_PER_FILE = 15")
                .isLessThanOrEqualTo(15);
    }

    @Test
    void chunkText_nullOrBlankText_returnsEmptyList() throws Exception {
        assertThat(invokeChunkText(null)).isEmpty();
        assertThat(invokeChunkText("   ")).isEmpty();
        assertThat(invokeChunkText("")).isEmpty();
    }

    // ── Helper methods ────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<String> invokeChunkText(String text) throws Exception {
        Method m = ClaudeWikiCompiler.class.getDeclaredMethod("chunkText", String.class);
        m.setAccessible(true);
        return (List<String>) m.invoke(compiler, text);
    }

    private Avatar mockAvatar(String name, String subject) {
        Avatar a = mock(Avatar.class);
        when(a.getId()).thenReturn("avatar-1");
        when(a.getName()).thenReturn(name);
        com.pally.domain.avatar.Subject s = mock(com.pally.domain.avatar.Subject.class);
        when(s.name()).thenReturn(subject);
        when(s.label()).thenReturn(subject);
        when(a.getSubject()).thenReturn(s);
        return a;
    }

    private KnowledgeFile mockFile(String name, String text) {
        KnowledgeFile f = mock(KnowledgeFile.class);
        when(f.getFileName()).thenReturn(name);
        when(f.getExtractedText()).thenReturn(text);
        return f;
    }
}
