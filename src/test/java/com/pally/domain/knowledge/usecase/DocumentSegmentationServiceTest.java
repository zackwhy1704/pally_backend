package com.pally.domain.knowledge.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.Segment;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The segmentation ladder must DEGRADE SILENTLY: bookmarks → one metered LLM call
 * → uniform ranges, never throwing, always returning ≥1 segment for real input.
 * Each rung is pinned here, plus the ≤25-page clamp and the text-window fallback.
 */
@ExtendWith(MockitoExtension.class)
class DocumentSegmentationServiceTest {

    @Mock private PdfTextExtractor pdf;
    @Mock private GeminiCompletionService gemini;
    private DocumentSegmentationService svc;

    private static final byte[] BYTES = new byte[]{1, 2, 3};

    @BeforeEach
    void setUp() {
        svc = new DocumentSegmentationService(pdf, gemini, new ObjectMapper());
    }

    @Test
    void bookmarksPresent_useThem_andNeverCallTheLlm() throws Exception {
        when(pdf.extractOutline(BYTES)).thenReturn(List.of(
                new PdfTextExtractor.Bookmark("Chapter 1", 1),
                new PdfTextExtractor.Bookmark("Chapter 2", 11)));
        when(pdf.extractPageRange(BYTES, 1, 10)).thenReturn("chapter one text");
        when(pdf.extractPageRange(BYTES, 11, 20)).thenReturn("chapter two text");

        List<Segment> segs = svc.segment(BYTES, KnowledgeFile.UploadType.PDF, "full", 20, "av1");

        assertThat(segs).extracting(Segment::title).containsExactly("Chapter 1", "Chapter 2");
        assertThat(segs.get(0).pageFrom()).isEqualTo(1);
        assertThat(segs.get(1).pageTo()).isEqualTo(20);
        verify(gemini, never()).complete(anyInt(), anyString(), anyString(), any());
    }

    @Test
    void fatChapter_splitsIntoClampedParts() throws Exception {
        when(pdf.extractOutline(BYTES)).thenReturn(List.of(
                new PdfTextExtractor.Bookmark("Big Chapter", 1),
                new PdfTextExtractor.Bookmark("Appendix", 31)));   // 30-page chapter → 2 parts of ≤25
        when(pdf.extractPageRange(eq(BYTES), anyInt(), anyInt())).thenReturn("text");

        List<Segment> segs = svc.segment(BYTES, KnowledgeFile.UploadType.PDF, "full", 35, "av1");

        assertThat(segs).extracting(Segment::title)
                .contains("Big Chapter (part 1/2)", "Big Chapter (part 2/2)", "Appendix");
        // the parts tile the chapter's pages contiguously, each ≤25 pages
        assertThat(segs.get(0).pageFrom()).isEqualTo(1);
        assertThat(segs.get(0).pageTo()).isEqualTo(25);
        assertThat(segs.get(1).pageFrom()).isEqualTo(26);
        assertThat(segs.get(1).pageTo()).isEqualTo(30);
    }

    @Test
    void noBookmarks_llmSucceeds_usesLlmRanges() throws Exception {
        when(pdf.extractOutline(BYTES)).thenReturn(List.of());
        when(pdf.extractPerPage(eq(BYTES), anyInt())).thenReturn(List.of("p1", "p2", "p3", "p4", "p5"));
        when(gemini.complete(anyInt(), anyString(), eq("segment"), any())).thenReturn(
                "[{\"title\":\"Intro\",\"pageFrom\":1,\"pageTo\":5},"
                + "{\"title\":\"Body\",\"pageFrom\":6,\"pageTo\":10}]");
        when(pdf.extractPageRange(eq(BYTES), anyInt(), anyInt())).thenReturn("slice");

        List<Segment> segs = svc.segment(BYTES, KnowledgeFile.UploadType.PDF, "full", 10, "av1");

        assertThat(segs).extracting(Segment::title).containsExactly("Intro", "Body");
        verify(gemini).complete(anyInt(), anyString(), eq("segment"), any());
    }

    @Test
    void noBookmarks_llmFails_fallsBackToUniformRanges() throws Exception {
        when(pdf.extractOutline(BYTES)).thenReturn(List.of());
        when(pdf.extractPerPage(eq(BYTES), anyInt())).thenReturn(List.of("p"));
        when(gemini.complete(anyInt(), anyString(), eq("segment"), any()))
                .thenThrow(new RuntimeException("model down"));
        when(pdf.extractPageRange(eq(BYTES), anyInt(), anyInt())).thenReturn("range text");

        List<Segment> segs = svc.segment(BYTES, KnowledgeFile.UploadType.PDF, "full", 30, "av1");

        // 30 pages → two uniform 25-page ranges; upload never fails.
        assertThat(segs).hasSize(2);
        assertThat(segs.get(0).title()).isEqualTo("Pages 1–25");
        assertThat(segs.get(1).title()).isEqualTo("Pages 26–30");
    }

    @Test
    void textUpload_usesUniformCharWindows_noPdfCalls() {
        String big = "x".repeat(120_000); // 3 × 50k windows
        List<Segment> segs = svc.segment(null, KnowledgeFile.UploadType.TEXT, big, 1, "av1");

        assertThat(segs).hasSize(3);
        assertThat(segs.get(0).text().length()).isEqualTo(50_000);
        assertThat(segs.get(2).text().length()).isEqualTo(20_000);
        verifyNoInteractions(pdf);
    }

    @Test
    void textFallback_backsOffToSentenceBoundary_notMidWord() {
        // A sentence boundary ". " sits just before the 50k mark, then a long unbroken
        // run crosses it. The OLD naive fixed-stride slice cut at exactly 50000 (mid-
        // word, inside the "b" run); the shared boundary-aware windower backs off to
        // the ". ". This assertion FAILS against the old uniformCharWindows loop.
        String text = "a".repeat(49_900) + ". " + "b".repeat(20_000);
        List<Segment> segs = svc.segment(null, KnowledgeFile.UploadType.TEXT, text, 1, "av1");

        assertThat(segs).hasSizeGreaterThanOrEqualTo(2);
        assertThat(segs.get(0).text()).endsWith(". ");           // real boundary, not mid-word
        assertThat(segs.get(0).text()).doesNotEndWith("b");      // did NOT cut inside the run
    }

    @Test
    void chunkFallback_adjacentChunksShareNoText_andHashDistinctly() {
        // overlap=0 → chapters tile as distinct ranges. Concatenation reproduces the
        // input (no shared boundary text), and adjacent slices hash distinctly, so the
        // per-chunk content_hash can never collide siblings (0.3's downstream concern).
        String text = ("Alpha beta gamma. " + "word ".repeat(300)).repeat(80); // > 50k, has boundaries
        List<Segment> segs = svc.segment(null, KnowledgeFile.UploadType.TEXT, text, 1, "av1");
        assertThat(segs).hasSizeGreaterThanOrEqualTo(2);

        assertThat(segs.stream().map(Segment::text).reduce("", String::concat)).isEqualTo(text);

        var dedup = new com.pally.domain.knowledge.ContentDeduplicator(
                org.mockito.Mockito.mock(
                        com.pally.infrastructure.persistence.knowledge.KnowledgeFileJpaRepository.class));
        String h0 = dedup.computeHash(segs.get(0).text());
        String h1 = dedup.computeHash(segs.get(1).text());
        assertThat(h0).isNotEqualTo(h1); // distinct slices → distinct hash → no exact-dup
    }
}
