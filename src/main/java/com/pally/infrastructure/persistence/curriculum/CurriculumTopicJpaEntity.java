package com.pally.infrastructure.persistence.curriculum;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "curriculum_topics")
@Getter
@Setter
@NoArgsConstructor
public class CurriculumTopicJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "curriculum_id", nullable = false, length = 36)
    private String curriculumId;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 200)
    private String slug;

    @Column(nullable = false)
    private int sequence = 0;

    @Column(name = "parent_topic_id", length = 36)
    private String parentTopicId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
