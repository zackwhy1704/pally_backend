package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.WikiPage;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(
    name = "wiki_pages",
    uniqueConstraints = @UniqueConstraint(columnNames = {"avatar_id", "slug"})
)
@Getter
@Setter
@NoArgsConstructor
public class WikiPageJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(nullable = false, length = 100)
    private String slug;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WikiPage.Certainty certainty;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "quality_score", nullable = false)
    private int qualityScore = 0;

    @Column(name = "human_correction", columnDefinition = "TEXT")
    private String humanCorrection;

    @Column(name = "correction_at")
    private Instant correctionAt;

    @Column(name = "is_human_verified", nullable = false)
    private boolean humanVerified = false;

    public static WikiPageJpaEntity fromDomain(WikiPage wp) {
        WikiPageJpaEntity e = new WikiPageJpaEntity();
        e.id = wp.getId();
        e.avatarId = wp.getAvatarId();
        e.slug = wp.getSlug();
        e.title = wp.getTitle();
        e.content = wp.getContent();
        e.certainty = wp.getCertainty();
        e.updatedAt = wp.getUpdatedAt();
        e.qualityScore = wp.getQualityScore();
        e.humanCorrection = wp.getHumanCorrection();
        e.correctionAt = wp.getCorrectionAt();
        e.humanVerified = wp.isHumanVerified();
        return e;
    }

    public WikiPage toDomain() {
        return WikiPage.reconstitute(id, avatarId, slug, title, content, certainty, updatedAt,
                qualityScore, humanCorrection, correctionAt, humanVerified);
    }
}
