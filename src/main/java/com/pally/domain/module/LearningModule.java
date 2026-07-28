package com.pally.domain.module;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain type mirroring {@code LearningModuleJpaEntity}.
 * No JPA annotations — persistence is handled by the adapter.
 */
@Getter
@Setter
@NoArgsConstructor
public class LearningModule {

    private String id;
    private String avatarId;
    private String classId;
    private String wikiPageSlug;
    private String title;
    private String stage;
    private String tier;
    private BigDecimal masteryPct;
    private Instant createdAt;
    private Instant completedAt;
    // Language the module's content was generated in ('en' | 'zh') — V124. Set at generation from the
    // source page (the material), so prove-answer evaluation follows the module's language. Default 'en'.
    private String contentLanguage = "en";
}
