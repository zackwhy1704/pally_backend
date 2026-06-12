package com.pally.infrastructure.persistence.challenge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A teacher-posted class challenge question. {@code options} is a JSON array
 * string for MCQ (null for free-text). The correct {@code answer} and the answer
 * distribution are server-withheld until {@code revealAt} passes.
 */
@Entity
@Table(name = "class_challenge")
@Getter
@Setter
@NoArgsConstructor
public class ClassChallengeJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    /** JSON array of MCQ options (stored as a JSON string), or null for a
     * free-text challenge. TEXT, not jsonb — the app serializes/deserializes it
     * with Jackson and never uses jsonb operators (a String↔jsonb bind mismatch
     * 500'd every insert). */
    @Column(columnDefinition = "TEXT")
    private String options;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "reveal_at", nullable = false)
    private Instant revealAt;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** True once the reveal push has been sent to answerers (dedup for the sweep). */
    @Column(nullable = false)
    private boolean notified = false;

    /** True when the reveal time has passed. */
    public boolean revealed() {
        return revealAt != null && !revealAt.isAfter(Instant.now());
    }
}
