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
@Table(name = "centre_enroll_codes")
@Getter
@Setter
@NoArgsConstructor
public class CentreEnrollCodeJpaEntity {

    @Id
    @Column(length = 12)
    private String code;

    @Column(name = "organization_id", nullable = false, length = 36)
    private String organizationId;

    @Column(name = "cohort_label", nullable = false, length = 120)
    private String cohortLabel;

    @Column(name = "max_uses", nullable = false)
    private int maxUses = 30;

    @Column(nullable = false)
    private int uses;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
