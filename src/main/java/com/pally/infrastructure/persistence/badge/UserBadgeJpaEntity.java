package com.pally.infrastructure.persistence.badge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "user_badges")
@Getter
@Setter
@NoArgsConstructor
public class UserBadgeJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "badge_type", nullable = false, length = 40)
    private String badgeType;

    @Column(name = "earned_at", nullable = false)
    private Instant earnedAt;
}
