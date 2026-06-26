package com.pally.infrastructure.persistence.quiz;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Server-held correct index for a generated quiz question, keyed by the
 * question id (which the client echoes back as the answer map key). Lets the
 * submit path grade authoritatively instead of trusting the client.
 */
@Entity
@Table(name = "quiz_answer_keys")
@Getter
@Setter
@NoArgsConstructor
public class QuizAnswerKeyJpaEntity {

    @Id
    @Column(name = "question_id", length = 100)
    private String questionId;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "correct_index", nullable = false)
    private int correctIndex;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
