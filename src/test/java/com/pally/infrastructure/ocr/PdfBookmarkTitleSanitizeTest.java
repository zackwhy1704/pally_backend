package com.pally.infrastructure.ocr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bookmark-title hardening that stops a long/newline-laden PDF bookmark from
 * overflowing the (pre-V122) varchar(255) chunk-title columns — the defect that 400'd the
 * whole 157-page "Sales Game" upload during segmentation.
 */
class PdfBookmarkTitleSanitizeTest {

    @Test
    void longTitle_truncatedTo200_soItCannotOverflowTheColumn() {
        String title = "A".repeat(300);
        String out = PdfTextExtractor.sanitizeBookmarkTitle(title);
        assertThat(out).hasSize(PdfTextExtractor.MAX_BOOKMARK_TITLE_CHARS);
        assertThat(out.length()).isLessThanOrEqualTo(200);
    }

    @Test
    void newlinesAndControlChars_collapsedToSingleSpaces() {
        String out = PdfTextExtractor.sanitizeBookmarkTitle("Chapter\n\t 1\r\n  Intro");
        assertThat(out).isEqualTo("Chapter 1 Intro");
        assertThat(out).doesNotContain("\n").doesNotContain("\t").doesNotContain("\r");
    }

    @Test
    void normalTitle_passesThroughTrimmed() {
        assertThat(PdfTextExtractor.sanitizeBookmarkTitle("  Introduction  "))
                .isEqualTo("Introduction");
    }
}
