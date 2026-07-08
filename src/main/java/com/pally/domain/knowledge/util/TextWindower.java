package com.pally.domain.knowledge.util;

import java.util.ArrayList;
import java.util.List;

/**
 * ONE boundary-aware text-windowing primitive, shared by both callers that cut a big
 * blob of text into bounded pieces:
 * <ul>
 *   <li>the compiler's token-limit WINDOWING (transient, in-memory; uses overlap so a
 *       concept split across a window boundary isn't lost — the windows share one
 *       fileId and merge by slug, so the overlap never double-counts), and</li>
 *   <li>the chapter picker's text/image fallback (persisted, distinct chapters; uses
 *       {@code overlap=0} so two chapters never share text — see below).</li>
 * </ul>
 *
 * <p>Each window backs off to a natural boundary (paragraph {@code \n\n} → newline
 * {@code \n} → sentence {@code ". "}) so it never cuts mid-word/mid-sentence, backing
 * off at most ~10% of {@code maxChars}. {@code overlap} carries that many chars into
 * the next window; it is capped at {@code maxChars/4} so a misconfigured overlap can
 * never make {@code start} crawl and explode the window count.
 *
 * <p><b>overlap semantics matter to the caller:</b> with {@code overlap > 0} adjacent
 * windows SHARE trailing/leading text — correct for the compiler (merged by slug), but
 * wrong for picker chapters (they compile into separate wiki pages, so shared text
 * would duplicate content and make page-range labels lie). Pass {@code overlap=0} when
 * the pieces must tile as distinct, non-overlapping ranges.
 */
public final class TextWindower {

    private TextWindower() {}

    /**
     * Split {@code text} into windows of at most {@code maxChars}, each backed off to a
     * natural boundary, carrying {@code overlap} chars into the next window. With
     * {@code overlap == 0} the windows tile contiguously (each window's start equals the
     * previous window's end) and their concatenation reproduces {@code text} exactly.
     */
    public static List<String> window(String text, int maxChars, int overlap) {
        // Cap overlap so each window always advances by ≥75% of maxChars — else a
        // misconfigured overlap ≥ maxChars would make `start` crawl and explode the
        // window count. (Compiler prod: 800 overlap vs 50k window — far below this cap.)
        int effOverlap = Math.max(0, Math.min(overlap, maxChars / 4));
        List<String> segs = new ArrayList<>();
        int n = text.length();
        int start = 0;
        while (start < n) {
            int end = Math.min(start + maxChars, n);
            if (end < n) {
                int floor = start + (maxChars * 9 / 10); // don't back off more than ~10%
                int b = lastBoundary(text, Math.max(floor, start + 1), end);
                if (b > start) {
                    end = b;
                }
            }
            segs.add(text.substring(start, end));
            if (end >= n) {
                break;
            }
            start = Math.max(end - effOverlap, start + 1);
        }
        return segs;
    }

    /** Last natural boundary in [floor, end): paragraph break, else newline, else sentence end. */
    private static int lastBoundary(String text, int floor, int end) {
        int para = text.lastIndexOf("\n\n", end - 1);
        if (para >= floor) return para + 2;
        int nl = text.lastIndexOf('\n', end - 1);
        if (nl >= floor) return nl + 1;
        int dot = text.lastIndexOf(". ", end - 1);
        if (dot >= floor) return dot + 2;
        return end;
    }
}
