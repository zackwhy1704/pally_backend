package com.pally.infrastructure.persistence.classroom;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "classroom_session")
@Getter
@Setter
@NoArgsConstructor
public class ClassroomSessionJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "teacher_id", nullable = false, length = 36)
    private String teacherId;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "join_code", nullable = false, length = 12)
    private String joinCode;

    @Column(name = "topic_slug", nullable = false, length = 200)
    private String topicSlug;

    @Column(name = "question_pool_json", nullable = false, columnDefinition = "text")
    private String questionPoolJson;

    @Column(name = "current_index", nullable = false)
    private int currentIndex;

    @Column(name = "hp_remaining", nullable = false)
    private int hpRemaining;

    @Column(name = "hp_max", nullable = false)
    private int hpMax;

    @Column(nullable = false)
    private boolean defeated;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;
}
