package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V124 wiki_pages.content_language mapping. 'en' comes from an explicit default at three layers
 * (DB column NOT NULL DEFAULT 'en', JPA field, domain field) — NEVER from a null quietly reaching
 * PromptLanguage.directive(null). This pins the "null must not slip through" requirement: even a
 * legacy row read with a null column surfaces 'en' by DESIGN.
 */
class WikiPageContentLanguageMappingTest {

    @Test
    void defaultsToEn_andRoundTripsZh() {
        WikiPage p = WikiPage.create("av", "slug", "Title", "content");
        assertThat(p.getContentLanguage()).isEqualTo("en"); // decision, not a null

        p.setContentLanguage("zh");
        WikiPage roundTripped = WikiPageJpaEntity.fromDomain(p).toDomain();
        assertThat(roundTripped.getContentLanguage()).isEqualTo("zh");
    }

    @Test
    void nullColumnOnLegacyRow_readsAsEnByDesign() {
        WikiPageJpaEntity e = WikiPageJpaEntity.fromDomain(WikiPage.create("av", "slug", "Title", "content"));
        e.setContentLanguage(null); // simulate a pre-V124 row materialised before the default applied

        assertThat(e.toDomain().getContentLanguage()).isEqualTo("en");
    }
}
