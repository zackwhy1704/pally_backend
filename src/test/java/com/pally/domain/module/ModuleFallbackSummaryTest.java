package com.pally.domain.module;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Bug 3: a degraded generation fell back to a RAW, mid-word-truncated slice of wiki
/// content (with markdown like `* **`) shown to the student as a question stem. The
/// fallback summary must be clean prose: no markdown artifacts, truncated at a word
/// boundary.
class ModuleFallbackSummaryTest {

    @Test
    void stripsMarkdownArtifacts_noRawSyntaxReachesTheStudent() {
        String out = ModuleContentGenerator.summarizeForFallback(
                "The author refers to sales as \"The Sales Game\" because * **Step Number One: Setting it up");
        assertThat(out)
                .doesNotContain("*")
                .doesNotContain("#")
                .doesNotContain("`")
                .doesNotContain("_");
    }

    @Test
    void truncatesAtWordBoundary_notMidWord() {
        String longText = "photosynthesis converts light energy into chemical energy stored in glucose "
                + "molecules within the chloroplasts of plant cells during a complex multi step biochemical process";
        String out = ModuleContentGenerator.summarizeForFallback(longText);

        assertThat(out).endsWith("…");
        String kept = out.substring(0, out.length() - 1); // drop the ellipsis
        String collapsed = longText.replaceAll("\\s+", " ").strip();
        assertThat(collapsed).startsWith(kept);
        // The char right after the kept prefix in the source is a space → we cut at a
        // word boundary, not mid-word.
        assertThat(collapsed.charAt(kept.length())).isEqualTo(' ');
        assertThat(kept).doesNotEndWith(" ");
    }

    @Test
    void blankOrMarkdownOnly_returnsSafeDefault() {
        assertThat(ModuleContentGenerator.summarizeForFallback(null)).isEqualTo("this topic");
        assertThat(ModuleContentGenerator.summarizeForFallback("   ")).isEqualTo("this topic");
        assertThat(ModuleContentGenerator.summarizeForFallback("* ** ## `")).isEqualTo("this topic");
    }
}
