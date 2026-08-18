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
 * JPA row mapping (syllabus_code, topic_tag) -> the SYLLABUS_PACK avatar. Stays inside
 * infrastructure; the adapter maps to/from the {@code SyllabusContentPack} domain type.
 */
@Entity
@Table(
        name = "syllabus_content_pack",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_syllabus_content_pack_code_topic",
                        columnNames = {"syllabus_code", "topic_tag"}),
                @UniqueConstraint(name = "uq_syllabus_content_pack_avatar",
                        columnNames = {"avatar_id"})
        },
        indexes = {
                @Index(name = "idx_syllabus_content_pack_status", columnList = "pack_status")
        })
@Getter
@Setter
@NoArgsConstructor
public class SyllabusContentPackJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "syllabus_code", nullable = false, length = 64)
    private String syllabusCode;

    @Column(name = "topic_tag", nullable = false, length = 100)
    private String topicTag;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "pack_status", nullable = false, length = 16)
    private String packStatus = "DRAFT";

    @Column(name = "source_license_note", length = 500)
    private String sourceLicenseNote;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
