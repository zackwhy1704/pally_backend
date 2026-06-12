package com.pally.infrastructure.persistence.module;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A student's anonymous "muddiest point" vote for a module: the one concept they
 * found trickiest. {@code userId} is stored purely for dedup (one vote per
 * student per module) and is NEVER exposed in any aggregate response.
 */
@Entity
@Table(name = "muddiest_vote")
@Getter
@Setter
@NoArgsConstructor
public class MuddiestVoteJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "module_id", nullable = false, length = 36)
    private String moduleId;

    @Column(name = "class_id", length = 36)
    private String classId;

    @Column(name = "concept_id", nullable = false, length = 255)
    private String conceptId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
