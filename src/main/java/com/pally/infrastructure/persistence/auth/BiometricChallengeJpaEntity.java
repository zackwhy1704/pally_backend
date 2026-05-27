package com.pally.infrastructure.persistence.auth;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "biometric_challenges")
@Getter @Setter @NoArgsConstructor
public class BiometricChallengeJpaEntity {
    @Id @Column(length = 36) private String id;
    @Column(name = "user_id", nullable = false) private String userId;
    @Column(name = "challenge_hash", nullable = false, length = 64) private String challengeHash;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean used;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
