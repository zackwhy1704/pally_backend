package com.pally.infrastructure.persistence.activity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "activity_log")
@Getter
@Setter
@NoArgsConstructor
public class ActivityLogJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "activity_type", nullable = false, length = 20)
    private String activityType;

    @Column(name = "avatar_id", length = 36)
    private String avatarId;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "xp_earned", nullable = false)
    private int xpEarned;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
