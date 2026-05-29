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
@Table(name = "curricula")
@Getter
@Setter
@NoArgsConstructor
public class CurriculumJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 30)
    private String subject;

    @Column(length = 20)
    private String grade;

    /// Null = built-in / shared curriculum. Non-null = uploaded by a user.
    @Column(name = "owner_user_id", length = 36)
    private String ownerUserId;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
