package com.pally.infrastructure.persistence.weakness;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Debounce + wins state for the weakness head, per (user_id, subject). Stays in
 * infrastructure; the adapter maps to the domain {@code WeaknessState} record.
 */
@Entity
@Table(
        name = "weakness_profile_state",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_weakness_state_user_subject",
                        columnNames = {"user_id", "subject"})
        })
@Getter
@Setter
@NoArgsConstructor
public class WeaknessProfileStateJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 32)
    private String subject;

    @Column(name = "weak_slugs", columnDefinition = "TEXT")
    private String weakSlugs;

    @Column(name = "recent_wins", columnDefinition = "TEXT")
    private String recentWins;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
