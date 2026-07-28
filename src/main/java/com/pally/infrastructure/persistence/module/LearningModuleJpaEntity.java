package com.pally.infrastructure.persistence.module;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "learning_module")
@Getter
@Setter
@NoArgsConstructor
public class LearningModuleJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "class_id", length = 36)
    private String classId;

    @Column(name = "wiki_page_slug", nullable = false, length = 255)
    private String wikiPageSlug;

    // Copied from the source wiki page title (LLM free text, now TEXT) — V94 TEXT.
    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false, length = 20)
    private String stage;

    @Column(nullable = false, length = 20)
    private String tier;

    /// Language the module's content was generated in ('en' | 'zh') — V124. Maps the
    /// learning_module.content_language column (NOT NULL DEFAULT 'en'); defaulted 'en' here too.
    @Column(name = "content_language", nullable = false, length = 10)
    private String contentLanguage = "en";

    @Column(name = "mastery_pct")
    private BigDecimal masteryPct;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
