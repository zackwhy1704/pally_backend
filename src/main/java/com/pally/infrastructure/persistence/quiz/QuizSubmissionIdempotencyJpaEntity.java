package com.pally.infrastructure.persistence.quiz;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/// One row per (user, idempotency key) — the once-only claim for a quiz submit.
/// result_json is NULL between the claim and the graded result being stored.
@Entity
@Table(name = "quiz_submission_idempotency")
@Getter @Setter @NoArgsConstructor
public class QuizSubmissionIdempotencyJpaEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private String userId;
    @Column(name = "idempotency_key", nullable = false, length = 64) private String idempotencyKey;
    @Column(name = "result_json", columnDefinition = "TEXT") private String resultJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
