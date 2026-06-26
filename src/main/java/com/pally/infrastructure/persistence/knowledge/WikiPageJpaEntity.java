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

    // slug/title come from the LLM compile draft (unbounded). Widened (V92) and
    // clamped in the domain so a long generated value can't fail the whole compile.
    @Column(nullable = false, length = 160)
    private String slug;

    @Column(nullable = false, columnDefinition = "TEXT")
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

    @Column(name = "last_retrieved_at")
    private java.time.Instant lastRetrievedAt;

    @Column(name = "quiz_use_count", nullable = false)
    private int quizUseCount = 0;

    @Column(name = "certainty_score", nullable = false)
    private double certaintyScore = 0.5;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WikiPage.Status status = WikiPage.Status.ACTIVE;

    @Column(name = "review_required", nullable = false)
    private boolean reviewRequired = false;

    @Column(name = "prerequisite_slugs", columnDefinition = "TEXT")
    private String prerequisiteSlugs;

    @Column(name = "has_conflict", nullable = false)
    private boolean hasConflict = false;

    /// True when the row came from the built-in starter pack (Batch B
    /// seed content) rather than a real user upload. The compiler can
    /// then prefer user content when both exist for the same slug.
    @Column(name = "is_seed", nullable = false)
    private boolean isSeed = false;

    /// Trusted-adult review provenance — CORRECTION (in-app human edit) or
    /// ADULT_REVIEW (tokenized reviewer approval). Null on AI-inferred pages.
    @Column(name = "verification_source", length = 20)
    private String verificationSource;

    /// Reviewer / verifier display name (no PII beyond a chosen name). Null
    /// until a human verifies the page.
    @Column(name = "verified_by", length = 80)
    private String verifiedBy;

    /// Short human-readable explanation of WHY this page conflicts. Null until
    /// the later-wave conflict-explanation extraction populates it.
    // LLM-generated free-text reason for a content conflict — unbounded (V93 TEXT).
    @Column(name = "conflict_note", columnDefinition = "TEXT")
    private String conflictNote;

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
        e.lastRetrievedAt = wp.getLastRetrievedAt();
        e.quizUseCount = wp.getQuizUseCount();
        e.certaintyScore = wp.getCertaintyScore();
        e.status = wp.getStatus() != null ? wp.getStatus() : WikiPage.Status.ACTIVE;
        e.reviewRequired = wp.isReviewRequired();
        e.prerequisiteSlugs = wp.getPrerequisiteSlugs();
        e.hasConflict = wp.isHasConflict();
        e.verificationSource = wp.getVerificationSource();
        e.verifiedBy = wp.getVerifiedBy();
        e.conflictNote = wp.getConflictNote();
        return e;
    }

    public WikiPage toDomain() {
        WikiPage wp = WikiPage.reconstitute(id, avatarId, slug, title, content, certainty, updatedAt,
                qualityScore, humanCorrection, correctionAt, humanVerified,
                lastRetrievedAt, quizUseCount, certaintyScore,
                status != null ? status : WikiPage.Status.ACTIVE,
                reviewRequired, prerequisiteSlugs, hasConflict);
        wp.setVerificationSource(verificationSource);
        wp.setVerifiedBy(verifiedBy);
        wp.setConflictNote(conflictNote);
        return wp;
    }
}
