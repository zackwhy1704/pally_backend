package com.pally.domain.centre;

import com.pally.infrastructure.ai.PromptLanguage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Teacher class-report language (decided: the CLASS's content_language). The reword is COPY-LEVEL:
 * the "plain English" prose-style line becomes language-neutral on non-en (so the model isn't told to
 * write English under a Chinese directive), while the English path stays byte-identical. Critically,
 * the honesty guideline ("Do NOT invent data not present above") must survive BOTH branches — the
 * reword must never touch the analytical/honesty logic. buildPrompt uses only a static formatter, so a
 * bare (null-dep) instance suffices.
 */
class ClassReportLanguageTest {

    private ClassReportGenerator generator() {
        return new ClassReportGenerator(null, null, null, null, null, null, null);
    }

    private static final String DATA = "Fractions: 82% avg; Decimals: 41% avg";

    @Test
    void englishReport_keepsPlainEnglishLine_noDirective_byteIdenticalPath() {
        String en = generator().buildPrompt(DATA, "en");
        assertThat(en).contains("- Write in plain English without headers or bullet points");
        assertThat(en).doesNotContain("flowing prose");
        assertThat(en).doesNotContain("华语");
        // Honesty guideline present.
        assertThat(en).contains("Do NOT invent data not present above");
    }

    @Test
    void chineseReport_rewordsToLanguageNeutral_appendsDirective_keepsHonesty() {
        String zh = generator().buildPrompt(DATA, "zh");
        // Reword: no "plain English" instruction fighting the directive.
        assertThat(zh).contains("- Write in flowing prose without headers or bullet points");
        assertThat(zh).doesNotContain("plain English");
        // Directive appended (report in the class's language).
        assertThat(zh).contains("华语");
        assertThat(zh).endsWith(PromptLanguage.directive("zh"));
        // The reword is copy-level ONLY: the honesty guideline still rides along.
        assertThat(zh).contains("Do NOT invent data not present above");
    }
}
