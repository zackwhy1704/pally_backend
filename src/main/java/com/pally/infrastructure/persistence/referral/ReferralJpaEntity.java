package com.pally.infrastructure.persistence.referral;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "referrals")
@Getter
@Setter
@NoArgsConstructor
public class ReferralJpaEntity {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ACTIVATED = "activated";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "referrer_user_id", nullable = false, length = 36)
    private String referrerUserId;

    @Column(name = "referee_user_id", nullable = false, length = 36, unique = true)
    private String refereeUserId;

    @Column(nullable = false, length = 20)
    private String status = STATUS_PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "activated_at")
    private Instant activatedAt;
}
