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
 * A student's locked answer to a class challenge. One per (challenge, user);
 * answers cannot be edited. {@code userId} is used for dedup and reveal-time
 * notification — it is NEVER exposed in the answer distribution.
 */
@Entity
@Table(name = "challenge_answer")
@Getter
@Setter
@NoArgsConstructor
public class ChallengeAnswerJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "challenge_id", nullable = false, length = 36)
    private String challengeId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String answer;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
