package com.pally.infrastructure.persistence.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** A detected fact-conflict on a wiki page, queued for teacher resolution (Part A). */
@Entity
@Table(name = "wiki_conflict")
@Getter
@Setter
public class WikiConflictJpaEntity {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String CONFIDENCE_DETERMINISTIC = "DETERMINISTIC";
    public static final String CONFIDENCE_PROSE = "PROSE";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(nullable = false, length = 160)
    private String slug;

    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(nullable = false, length = 20)
    private String confidence;

    @Column(nullable = false, length = 20)
    private String status = STATUS_OPEN;

    @Column(name = "canonical_value", columnDefinition = "TEXT")
    private String canonicalValue;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by", length = 36)
    private String resolvedBy;
}
