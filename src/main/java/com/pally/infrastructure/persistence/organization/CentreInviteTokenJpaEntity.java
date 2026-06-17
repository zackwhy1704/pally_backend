package com.pally.infrastructure.persistence.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "centre_invite_tokens")
@Getter
@Setter
@NoArgsConstructor
public class CentreInviteTokenJpaEntity {

    @Id
    @Column(name = "token", length = 64)
    private String token;

    @Column(name = "centre_name", nullable = false)
    private String centreName;

    @Column(name = "contact_email", nullable = false)
    private String contactEmail;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "accepted_by", length = 36)
    private String acceptedBy;

    @Column(name = "org_id", length = 36)
    private String orgId;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
