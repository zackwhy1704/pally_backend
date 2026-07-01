package com.pally.domain.knowledge;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contextual chunk summary is prepended to a page's grounding text (the
 * retrieval lift), while plain content is unchanged when no context exists.
 */
class WikiPageContextTest {

    @Test
    void groundingText_prependsContextWhenPresent() {
        WikiPage p = WikiPage.create("av", "photosynthesis", "Photosynthesis", "Plants make food.");
        p.setContext("Covers photosynthesis within Biology.");

        String g = p.groundingText();

        assertThat(g).startsWith("_Covers photosynthesis within Biology._");
        assertThat(g).contains("Plants make food.");
        assertThat(p.getContext()).isEqualTo("Covers photosynthesis within Biology.");
    }

    @Test
    void groundingText_isPlainContentWhenNoContext() {
        WikiPage p = WikiPage.create("av", "s", "T", "Body only.");
        assertThat(p.groundingText()).isEqualTo("Body only.");
    }

    @Test
    void groundingText_prefersHumanCorrectionOverContent() {
        WikiPage p = WikiPage.create("av", "s", "T", "AI body.");
        p.applyHumanCorrection("Teacher fixed body.");
        p.setContext("ctx");

        String g = p.groundingText();

        assertThat(g).contains("Teacher fixed body.").doesNotContain("AI body.");
        assertThat(g).startsWith("_ctx_");
    }
}
