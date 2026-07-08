package com.pally.infrastructure.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Single-use auth challenge (social linking code / password-reset token). See V116. */
@Entity
@Table(name = "auth_challenges")
@Getter
@Setter
@NoArgsConstructor
public class AuthChallengeJpaEntity {

    @Id
    private String id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(nullable = false, length = 20)
    private String purpose; // LINK_SOCIAL | PASSWORD_RESET

    @Column(name = "code_hash", nullable = false, length = 128)
    private String codeHash;

    @Column(length = 20)
    private String provider;

    @Column(name = "provider_sub")
    private String providerSub;

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING | CONSUMED | EXPIRED

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
