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
}
