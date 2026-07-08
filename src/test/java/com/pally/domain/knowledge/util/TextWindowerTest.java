package com.pally.domain.knowledge.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared windowing primitive. The two callers differ ONLY in overlap: the
 * compiler passes 800 (windows merge by slug, overlap gives cross-boundary context),
 * the picker passes 0 (chapters must tile as distinct, non-overlapping ranges). Both
 * back off to a real boundary so neither cuts mid-word.
 */
class TextWindowerTest {

    @Test
    void overlapZero_tilesContiguously_concatEqualsOriginal_noSharedText() {
        String text = ("Sentence one. Sentence two. " + "word ".repeat(400)).repeat(60); // > 50k
        List<String> w = TextWindower.window(text, 50_000, 0);

        assertThat(w.size()).isGreaterThanOrEqualTo(2);
        // tiles exactly: concatenation reproduces the input, so adjacent windows
        // share NO text (start_{i+1} == end_i).
        assertThat(String.join("", w)).isEqualTo(text);
        assertThat(w).allSatisfy(s -> assertThat(s.length()).isLessThanOrEqualTo(50_000));
    }

    @Test
    void overlapPositive_adjacentWindowsShareOverlapText_theCompilerBehavior() {
        String text = "x".repeat(130_000); // no boundaries → clean fixed windows
        List<String> w = TextWindower.window(text, 50_000, 800);

        // window 0 ends at 50000; window 1 starts at 50000 - 800 = 49200, so the last
        // 800 chars of window 0 equal the first 800 chars of window 1 (shared context).
        String tailOf0 = w.get(0).substring(w.get(0).length() - 800);
        String headOf1 = w.get(1).substring(0, 800);
        assertThat(headOf1).isEqualTo(tailOf0);
    }

    @Test
    void overlapCap_hugeOverlapCannotExplodeWindowCount() {
        // overlap >> maxChars/4 must be capped so start still advances ≥75% of maxChars.
        String text = "y".repeat(200_000);
        List<String> w = TextWindower.window(text, 40_000, 1_000_000);
        // capped overlap = 40000/4 = 10000 → advance ≥ 30000 → ≤ ~7 windows, not thousands.
        assertThat(w.size()).isLessThan(10);
    }

    @Test
    void backsOffToBoundary_paragraphThenNewlineThenSentence() {
        String para = "a".repeat(49_500) + "\n\n" + "b".repeat(20_000);
        assertThat(TextWindower.window(para, 50_000, 800).get(0)).endsWith("\n\n");

        String sentence = "a".repeat(49_900) + ". " + "b".repeat(20_000);
        assertThat(TextWindower.window(sentence, 50_000, 0).get(0)).endsWith(". ");
    }

    @Test
    void noBoundaries_isNoOp_windowsAreExactMaxChars() {
        // all-x has no \n\n / \n / ". " → backoff never fires → exact-size windows.
        // This is the proof the boundary-aware path is byte-identical to the old naive
        // fixed-stride slice on unstructured input (why the existing tests stay green).
        List<String> w = TextWindower.window("x".repeat(120_000), 50_000, 0);
        assertThat(w).hasSize(3);
        assertThat(w.get(0)).hasSize(50_000);
        assertThat(w.get(1)).hasSize(50_000);
        assertThat(w.get(2)).hasSize(20_000);
    }
}
