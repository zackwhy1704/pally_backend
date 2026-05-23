package com.pally.infrastructure.persistence.quiz;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "quiz_answer_records")
@Getter
@Setter
@NoArgsConstructor
public class QuizAnswerRecordJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Column(name = "user_answer", nullable = false, columnDefinition = "TEXT")
    private String userAnswer;

    @Column(name = "correct_answer", nullable = false, columnDefinition = "TEXT")
    private String correctAnswer;

    @Column(name = "is_correct", nullable = false)
    private boolean correct;

    @Column(name = "error_type", length = 20)
    private String errorType;

    @Column(name = "topic_slug", length = 200)
    private String topicSlug;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
