package com.pally.infrastructure.persistence.syllabus;

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
 * JPA row for a syllabus_content_pack_alias (V130). Stays inside infrastructure; the
 * adapter maps to/from the {@code SyllabusContentPackAlias} domain type.
 */
@Entity
@Table(
        name = "syllabus_content_pack_alias",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_syllabus_content_pack_alias_code_topic",
                        columnNames = {"syllabus_code", "topic_tag"})
        },
        indexes = {
                @Index(name = "idx_syllabus_content_pack_alias_pack", columnList = "pack_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class SyllabusContentPackAliasJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "pack_id", nullable = false, length = 36)
    private String packId;

    @Column(name = "syllabus_code", nullable = false, length = 64)
    private String syllabusCode;

    @Column(name = "topic_tag", nullable = false, length = 100)
    private String topicTag;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
