package com.pally.infrastructure.ai;

import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quiz's sourcePageSlug must resolve to the canonical WikiPage.getSlug()
 * (hyphenated), not the model-echoed title — otherwise the weakness/mastery
 * signal keys on a mismatched topic identity.
 */
class ClaudeQuizGeneratorSlugTest {

    private final List<WikiPage> pages = List.of(
            WikiPage.create("av", "dividing-fractions", "Dividing Fractions", "..."),
            WikiPage.create("av", "simplifying-ratios", "Simplifying Ratios", "..."));

    @Test
    void mapsModelEchoedTitleBackToRealSlug() {
        assertThat(ClaudeQuizGenerator.resolveSlug(pages, "Dividing Fractions"))
                .isEqualTo("dividing-fractions");
    }

    @Test
    void keepsAnExactSlug() {
        assertThat(ClaudeQuizGenerator.resolveSlug(pages, "simplifying-ratios"))
                .isEqualTo("simplifying-ratios");
    }

    @Test
    void normalisesCaseAndSeparators() {
        assertThat(ClaudeQuizGenerator.resolveSlug(pages, "Simplifying_Ratios"))
                .isEqualTo("simplifying-ratios");
    }

    @Test
    void fallsBackToSolePageSlugOnUnknownValue() {
        List<WikiPage> one = List.of(
                WikiPage.create("av", "dividing-fractions", "Dividing Fractions", "..."));
        assertThat(ClaudeQuizGenerator.resolveSlug(one, "garbled model output"))
                .isEqualTo("dividing-fractions");
    }

    // ── zh near-miss romanisation (the E2E finding) ──────────────────────────
    // The compiler emits "wo-de-linli-reading" (里 = lǐ → "li"); the quiz-gen echoes
    // "wo-de-linri-reading" — one char off. Multi-page, so the sole-page fallback
    // can't save it. It must resolve to the REAL page, never leak the dangling slug.
    private final List<WikiPage> zhPages = List.of(
            WikiPage.create("av", "wo-de-linli-reading", "我的邻里：阅读短文", "..."),
            WikiPage.create("av", "wo-de-linli-vocabulary", "我的邻里：词语学习", "..."));

    @Test
    void resolvesNearMissRomanisationToTheRealSlug() {
        assertThat(ClaudeQuizGenerator.resolveSlug(zhPages, "wo-de-linri-reading"))
                .isEqualTo("wo-de-linli-reading");
    }

    @Test
    void multiPageUnresolvableNeverReturnsADanglingSlug() {
        // Total garbage against multiple pages → must still be a REAL page slug
        // (a non-existent sourcePageSlug silently breaks source-jump + mastery).
        String resolved = ClaudeQuizGenerator.resolveSlug(zhPages, "zzz-not-a-page-at-all");
        assertThat(zhPages.stream().map(WikiPage::getSlug)).contains(resolved);
    }
}
