package com.pally.infrastructure.persistence.marking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA row mapping (org_id, subject) → the MARKING_CORPUS avatar. Stays inside
 * infrastructure; the adapter maps to/from the {@code MarkingCorpus} domain type.
 */
@Entity
@Table(
        name = "marking_corpus",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_marking_corpus_org_subject",
                        columnNames = {"org_id", "subject"})
        },
        indexes = {
                @Index(name = "idx_marking_corpus_avatar", columnList = "avatar_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class MarkingCorpusJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "org_id", nullable = false, length = 36)
    private String orgId;

    @Column(nullable = false, length = 30)
    private String subject;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
