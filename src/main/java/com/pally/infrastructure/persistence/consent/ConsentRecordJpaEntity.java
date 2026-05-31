package com.pally.infrastructure.persistence.consent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "consent_records")
@Getter @Setter @NoArgsConstructor
public class ConsentRecordJpaEntity {

    @Id @Column(length = 36)
    private String id;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "consenter", nullable = false, length = 20)
    private String consenter; // SELF | PARENT

    @Column(name = "method", nullable = false, length = 20)
    private String method; // EMAIL_LINK | CHECKBOX

    @Column(name = "purposes", nullable = false, columnDefinition = "TEXT")
    private String purposes; // JSON list

    @Column(name = "policy_version", nullable = false, length = 20)
    private String policyVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;
}
