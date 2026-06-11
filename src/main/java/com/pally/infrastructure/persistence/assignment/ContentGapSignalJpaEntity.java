package com.pally.infrastructure.persistence.assignment;

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
@Table(name = "content_gap_signal")
@Getter
@Setter
@NoArgsConstructor
public class ContentGapSignalJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", length = 36)
    private String classId;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "query_text", nullable = false, length = 1000)
    private String queryText;

    @Column(name = "matched_confidence")
    private BigDecimal matchedConfidence;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
