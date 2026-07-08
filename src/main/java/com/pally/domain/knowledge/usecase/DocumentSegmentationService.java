package com.pally.domain.knowledge.usecase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.Segment;
import com.pally.infrastructure.ai.GeminiCompletionService;
import com.pally.infrastructure.ocr.PdfTextExtractor;
import com.pally.shared.json.JsonExtraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Splits an oversized upload into chapter segments — the pickable units of the
 * chunked-compile flow. DEGRADES SILENTLY, never throws, never blocks an upload:
 *
 * <ol>
 *   <li><b>PDF bookmarks</b> — the document's own outline (page-accurate).</li>
 *   <li><b>LLM titles</b> — ONE metered {@code segment} Flash call
 *       (thinkingBudget=0, extraction bucket) over a page-marked sample, returning
 *       chapter page ranges. Only for PDFs small enough to sample whole.</li>
 *   <li><b>Uniform ranges</b> — {@value #PAGES_PER_CHUNK}-page (PDF) or
 *       {@value #CHUNK_MAX_CHARS}-char (text) windows titled "Pages X–Y".</li>
 * </ol>
 *
 * Every segment is clamped to {@value #PAGES_PER_CHUNK} pages so a fat "chapter"
 * splits into parts ("Ch 3 (part 2/3)") and the per-chunk compile stays bounded.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentSegmentationService {

    /** Chunk quality unit — matches the segment trigger. A chunk larger than this
     *  is split; the compile's own within-file segmentation is a further backstop. */
    public static final int CHUNK_MAX_CHARS = 50_000;
    static final int PAGES_PER_CHUNK = 25;
    static final int CHARS_PER_PAGE = 2_000;      // for deriving page numbers from text offsets
    static final int LLM_SAMPLE_MAX_PAGES = 60;   // only LLM-segment PDFs we can sample whole
    static final int LLM_SAMPLE_MAX_CHARS = 80_000;
    private static final int SEGMENT_TOKENS = 1_024;

    private final PdfTextExtractor pdfTextExtractor;
    private final GeminiCompletionService geminiCompletion;
    private final ObjectMapper objectMapper;

    /**
     * Segment from STORED extracted text alone (no original bytes) — for the compile
     * sweep's legacy-file guard, where a pre-existing oversized READY file must be
     * split without re-downloading. Uniform char windows only (no bookmarks/LLM), so
     * a grandfathered file becomes pickable even if titles are generic "Pages X–Y".
     */
    public List<Segment> segmentFromText(String extractedText) {
        return uniformCharWindows(extractedText);
    }

    /**
     * @return an ordered list of ≥1 segments. Never empty for a non-blank input,
     *         never throws — the uniform ladder is the guaranteed floor.
     */
    public List<Segment> segment(byte[] fileBytes, KnowledgeFile.UploadType type,
                                 String extractedText, int pageCount, String avatarId) {
        try {
            if (type == KnowledgeFile.UploadType.PDF && pageCount > 0) {
                return segmentPdf(fileBytes, extractedText, pageCount, avatarId);
            }
            // TEXT / PHOTO: no page structure → uniform char windows.
            return uniformCharWindows(extractedText);
        } catch (Exception e) {
            // Absolute backstop — segmentation must NEVER fail an upload.
            log.warn("[Segment] segmentation failed, using single-range fallback: {}", e.toString());
            return uniformCharWindows(extractedText);
        }
    }

    // ── PDF ladder ──────────────────────────────────────────────────────────

    private List<Segment> segmentPdf(byte[] bytes, String extractedText, int pageCount, String avatarId) {
        // 1. Bookmarks.
        List<PdfTextExtractor.Bookmark> marks = pdfTextExtractor.extractOutline(bytes);
        if (marks.size() >= 2) {
            List<Segment> segs = fromBookmarks(bytes, marks, pageCount);
            if (segs.size() >= 2) {
                log.info("[Segment] {} chapters from PDF bookmarks", segs.size());
                return segs;
            }
        }
        // 2. LLM titles (bounded to sampleable PDFs).
        if (pageCount <= LLM_SAMPLE_MAX_PAGES) {
            List<Segment> segs = fromLlm(bytes, pageCount, avatarId);
            if (segs.size() >= 2) {
                log.info("[Segment] {} chapters from LLM segmentation", segs.size());
                return segs;
            }
        }
        // 3. Uniform page ranges.
        log.info("[Segment] falling back to uniform {}-page ranges ({} pages)", PAGES_PER_CHUNK, pageCount);
        return uniformPageRanges(bytes, pageCount);
    }

    private List<Segment> fromBookmarks(byte[] bytes, List<PdfTextExtractor.Bookmark> marks, int pageCount) {
        List<Segment> out = new ArrayList<>();
        for (int i = 0; i < marks.size(); i++) {
            int from = marks.get(i).startPage();
            int to = (i + 1 < marks.size()) ? marks.get(i + 1).startPage() - 1 : pageCount;
            if (to < from) continue; // out-of-order / duplicate destination
            out.addAll(splitPageRange(bytes, marks.get(i).title(), from, Math.min(to, pageCount)));
        }
        return out;
    }

    private List<Segment> fromLlm(byte[] bytes, int pageCount, String avatarId) {
        try {
            List<String> pages = pdfTextExtractor.extractPerPage(bytes, LLM_SAMPLE_MAX_PAGES);
            StringBuilder sample = new StringBuilder();
            for (int p = 0; p < pages.size() && sample.length() < LLM_SAMPLE_MAX_CHARS; p++) {
                sample.append("[Page ").append(p + 1).append("]\n").append(pages.get(p)).append("\n");
            }
            String prompt = """
                    You are splitting a document into its chapters/sections for a study app.
                    The text below is page-marked with [Page N] headers.
                    Return ONLY a JSON array of the top-level chapters in reading order:
                    [{"title": "Chapter title", "pageFrom": 1, "pageTo": 8}, ...]
                    Rules: pageFrom/pageTo are 1-based inclusive page numbers; contiguous and
                    non-overlapping; cover pages 1..%d; 2-12 chapters is typical. No prose.

                    DOCUMENT:
                    %s
                    """.formatted(pageCount, sample);

            String raw = geminiCompletion.complete(SEGMENT_TOKENS, prompt, "segment", avatarId);
            List<Map<String, Object>> parsed = JsonExtraction.parseObjects(objectMapper, raw);

            List<Segment> out = new ArrayList<>();
            int lastTo = 0;
            for (Map<String, Object> m : parsed) {
                Integer from = asInt(m.get("pageFrom"));
                Integer to = asInt(m.get("pageTo"));
                Object t = m.get("title");
                if (from == null || to == null || t == null) continue;
                int f = Math.max(1, from);
                int e = Math.min(pageCount, to);
                if (e < f) continue;
                out.addAll(splitPageRange(bytes, String.valueOf(t), f, e));
                lastTo = Math.max(lastTo, e);
            }
            if (out.isEmpty()) return List.of();
            // If the model under-covered, append uniform ranges for the tail so no page is lost.
            if (lastTo < pageCount) {
                out.addAll(uniformPageRangesFrom(bytes, lastTo + 1, pageCount));
            }
            return out;
        } catch (Exception e) {
            log.warn("[Segment] LLM segmentation failed, falling through: {}", e.toString());
            return List.of();
        }
    }

    private List<Segment> uniformPageRanges(byte[] bytes, int pageCount) {
        return uniformPageRangesFrom(bytes, 1, pageCount);
    }

    private List<Segment> uniformPageRangesFrom(byte[] bytes, int start, int pageCount) {
        List<Segment> out = new ArrayList<>();
        for (int from = start; from <= pageCount; from += PAGES_PER_CHUNK) {
            int to = Math.min(from + PAGES_PER_CHUNK - 1, pageCount);
            String text = safePageText(bytes, from, to);
            if (text.isBlank()) continue;
            out.add(new Segment("Pages " + from + "–" + to, from, to, text));
        }
        return out;
    }

    /** Split a chapter's page range into ≤PAGES_PER_CHUNK-page parts, extracting each. */
    private List<Segment> splitPageRange(byte[] bytes, String title, int from, int to) {
        int spanPages = to - from + 1;
        int parts = (int) Math.ceil(spanPages / (double) PAGES_PER_CHUNK);
        List<Segment> out = new ArrayList<>();
        int idx = 1;
        for (int p = from; p <= to; p += PAGES_PER_CHUNK) {
            int end = Math.min(p + PAGES_PER_CHUNK - 1, to);
            String text = safePageText(bytes, p, end);
            if (text.isBlank()) { idx++; continue; }
            String partTitle = parts > 1 ? title + " (part " + idx + "/" + parts + ")" : title;
            out.add(new Segment(partTitle, p, end, text));
            idx++;
        }
        return out;
    }

    private String safePageText(byte[] bytes, int from, int to) {
        try {
            return pdfTextExtractor.extractPageRange(bytes, from, to);
        } catch (Exception e) {
            log.warn("[Segment] page-range extract failed [{}-{}]: {}", from, to, e.toString());
            return "";
        }
    }

    // ── Text / image fallback ────────────────────────────────────────────────

    /** Uniform char windows for text/image uploads (no page structure). Page
     *  numbers are derived from char offsets so the picker still shows "Pages X–Y". */
    private List<Segment> uniformCharWindows(String text) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        int len = text.length();
        for (int from = 0; from < len; from += CHUNK_MAX_CHARS) {
            int end = Math.min(from + CHUNK_MAX_CHARS, len);
            int pageFrom = from / CHARS_PER_PAGE + 1;
            int pageTo = Math.max(pageFrom, (end - 1) / CHARS_PER_PAGE + 1);
            out.add(new Segment("Pages " + pageFrom + "–" + pageTo, pageFrom, pageTo,
                    text.substring(from, end)));
        }
        return out;
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return o == null ? null : Integer.valueOf(o.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
