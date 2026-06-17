package com.pally.infrastructure.persistence.organization;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "org_staff",
       uniqueConstraints = @UniqueConstraint(columnNames = {"org_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
public class OrgStaffJpaEntity {

    public static final String STATUS_ACTIVE  = "ACTIVE";
    public static final String STATUS_REMOVED = "REMOVED";
    public static final String ROLE_STAFF     = "STAFF";

    @Id
    @Column(name = "id", length = 36)
    private String id;

    @Column(name = "org_id", nullable = false, length = 36)
    private String orgId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "role", nullable = false, length = 10)
    private String role = ROLE_STAFF;

    @Column(name = "status", nullable = false, length = 10)
    private String status = STATUS_ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "removed_at")
    private Instant removedAt;
}
