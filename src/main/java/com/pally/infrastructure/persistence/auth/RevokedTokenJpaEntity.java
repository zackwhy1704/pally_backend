package com.pally.infrastructure.persistence.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persists revoked JWT IDs so that tokens issued before account deletion are
 * immediately rejected even within their remaining validity window.
 *
 * <p>Rows can be pruned once {@code expires_at < NOW()} — the JWT library will
 * reject expired tokens anyway, so stale rows are just dead weight.
 */
@Entity
@Table(name = "revoked_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RevokedTokenJpaEntity {

    /// The JWT ID claim (jti). UUIDs are 36 chars.
    @Id
    @Column(length = 36, nullable = false)
    private String jti;

    @Column(name = "revoked_at", nullable = false)
    private Instant revokedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
