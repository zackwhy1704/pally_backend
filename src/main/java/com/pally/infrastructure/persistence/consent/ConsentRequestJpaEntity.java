package com.pally.infrastructure.persistence.consent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "consent_requests")
@Getter @Setter @NoArgsConstructor
public class ConsentRequestJpaEntity {

    public static final String STATUS_PENDING  = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_DECLINED = "DECLINED";
    public static final String STATUS_EXPIRED  = "EXPIRED";

    @Id @Column(length = 36)
    private String id;

    @Column(name = "child_user_id", nullable = false, length = 36)
    private String childUserId;

    @Column(name = "parent_email", nullable = false, length = 255)
    private String parentEmail;

    @Column(name = "token", nullable = false, unique = true, length = 128)
    private String token;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "approved_at")
    private Instant approvedAt;
}
