package com.pally.infrastructure.persistence.module;

import com.pally.domain.module.LearningModule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V124 learning_module.content_language mapping (1b.5b). 'en' by decision at three layers (DB column
 * NOT NULL DEFAULT, JPA field, domain field); a null column reads 'en'. toDomain/toEntity don't touch
 * the injected repo, so a null-repo adapter exercises the mapping purely.
 */
class LearningModuleContentLanguageMappingTest {

    private final LearningModuleRepositoryAdapter adapter = new LearningModuleRepositoryAdapter(null);

    @Test
    void defaultsToEn_andRoundTripsZh() {
        LearningModule m = new LearningModule();
        assertThat(m.getContentLanguage()).isEqualTo("en"); // decision, not a null

        m.setContentLanguage("zh");
        LearningModule roundTripped = adapter.toDomain(adapter.toEntity(m));
        assertThat(roundTripped.getContentLanguage()).isEqualTo("zh");
    }

    @Test
    void nullColumnOnLegacyRow_readsAsEnByDesign() {
        LearningModuleJpaEntity e = adapter.toEntity(new LearningModule());
        e.setContentLanguage(null); // pre-V124 row materialised before the default applied
        assertThat(adapter.toDomain(e).getContentLanguage()).isEqualTo("en");
    }
}
