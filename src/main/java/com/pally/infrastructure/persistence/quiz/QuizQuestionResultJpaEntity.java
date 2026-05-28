package com.pally.infrastructure.persistence.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "quiz_question_results")
@Getter
@Setter
@NoArgsConstructor
public class QuizQuestionResultJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "question_id", nullable = false, length = 100)
    private String questionId;

    @Column(name = "topic_slug", length = 200)
    private String topicSlug;

    @Column(name = "was_correct", nullable = false)
    private boolean wasCorrect;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
