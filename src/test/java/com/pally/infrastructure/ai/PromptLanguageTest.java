package com.pally.infrastructure.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the byte-identical-English invariant at its source: for content_language 'en' (and
 * null/blank/unknown) the directive is EMPTY, so every prompt that appends it is byte-identical
 * to the pre-V124 English prompt. Only 'zh' yields the Singapore-conventions instruction.
 */
class PromptLanguageTest {

    @Test
    void english_yieldsEmptyDirective_soPromptsStayByteIdentical() {
        assertThat(PromptLanguage.directive("en")).isEmpty();
        assertThat(PromptLanguage.directive("EN")).isEmpty();
        assertThat(PromptLanguage.directive(" en ")).isEmpty();
    }

    @Test
    void nullBlankAndUnknown_degradeToEnglish_emptyDirective() {
        assertThat(PromptLanguage.directive(null)).isEmpty();
        assertThat(PromptLanguage.directive("")).isEmpty();
        assertThat(PromptLanguage.directive("   ")).isEmpty();
        assertThat(PromptLanguage.directive("fr")).isEmpty(); // unrecognised → English, not a half-prompt
    }

    @Test
    void chinese_yieldsSingaporeConventionsInstruction() {
        String d = PromptLanguage.directive("zh");
        assertThat(d).isNotEmpty();
        // The conventions that separate a P3 华文 class from a mainland textbook.
        assertThat(d).contains("Simplified Chinese");
        assertThat(d).contains("华语");
        assertThat(d).contains("巴士");
        assertThat(d).contains("Hanyu Pinyin");
        assertThat(d).contains("Traditional Chinese characters"); // "Never output Traditional Chinese characters…"
        // Contract-preservation: JSON keys / SOURCE: marker stay ASCII.
        assertThat(d).contains("SOURCE:");
        assertThat(d).containsIgnoringCase("JSON key");
        // One-shot-safe rules folded in from the operator directive.
        assertThat(d).contains("Be consistent");
        assertThat(d).contains("Cantonese");
    }

    @Test
    void chinese_isCaseAndWhitespaceInsensitive() {
        assertThat(PromptLanguage.directive("ZH")).isEqualTo(PromptLanguage.directive("zh"));
        assertThat(PromptLanguage.directive(" zh ")).isEqualTo(PromptLanguage.directive("zh"));
    }

    @Test
    void isTranslated_matchesDirectiveEmptiness() {
        assertThat(PromptLanguage.isTranslated("en")).isFalse();
        assertThat(PromptLanguage.isTranslated(null)).isFalse();
        assertThat(PromptLanguage.isTranslated("zh")).isTrue();
    }
}
