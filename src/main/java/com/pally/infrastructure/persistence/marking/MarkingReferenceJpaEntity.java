package com.pally.infrastructure.persistence.marking;

import com.pally.domain.marking.MarkingReferenceKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA row for a marking reference. Stays inside infrastructure — the adapter
 * maps to/from the {@code MarkingReference} domain type at the boundary.
 * {@code filesJson} holds the artifact list as JSON (mapped in the adapter).
 */
@Entity
@Table(
        name = "marking_reference",
        indexes = {
                @Index(name = "idx_marking_reference_class", columnList = "class_id,created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class MarkingReferenceJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private MarkingReferenceKind kind;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "files_json", nullable = false, columnDefinition = "TEXT")
    private String filesJson;

    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
