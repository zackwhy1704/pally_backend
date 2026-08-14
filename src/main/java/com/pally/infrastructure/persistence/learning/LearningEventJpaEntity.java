package com.pally.infrastructure.persistence.learning;

import com.pally.domain.learning.LearningEventProvenance;
import com.pally.domain.learning.LearningEventSource;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "learning_event")
@Getter
@Setter
@NoArgsConstructor
public class LearningEventJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "avatar_id", length = 36)
    private String avatarId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LearningEventSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private LearningEventProvenance provenance;

    @Column(name = "topic_slug", length = 200)
    private String topicSlug;

    @Column(precision = 5, scale = 2)
    private BigDecimal score;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "source_row_id", nullable = false, length = 36)
    private String sourceRowId;
}
