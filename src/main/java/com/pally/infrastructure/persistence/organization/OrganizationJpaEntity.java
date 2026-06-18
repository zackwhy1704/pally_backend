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
@Table(name = "organizations")
@Getter
@Setter
@NoArgsConstructor
public class OrganizationJpaEntity {

    public static final String TYPE_TUITION_CENTRE = "TUITION_CENTRE";

    // B2B tier constants
    public static final String TIER_NONE    = "NONE";
    public static final String TIER_SOLO    = "SOLO";
    public static final String TIER_CENTRE  = "CENTRE";
    public static final String TIER_SCHOOL  = "SCHOOL";

    // B2B subscription status constants
    public static final String STATUS_NONE     = "NONE";
    public static final String STATUS_PILOT    = "PILOT";
    public static final String STATUS_ACTIVE   = "ACTIVE";
    public static final String STATUS_EXPIRED  = "EXPIRED";
    public static final String STATUS_CANCELED = "CANCELED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 40)
    private String type = TYPE_TUITION_CENTRE;

    @Column(name = "owner_user_id", nullable = false, length = 36)
    private String ownerUserId;

    @Column(name = "seat_limit", nullable = false)
    private int seatLimit = 30;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // ── B2B billing state (V82) ────────────────────────────────────────────────

    @Column(nullable = false, length = 20)
    private String tier = TIER_NONE;

    @Column(name = "sub_status", nullable = false, length = 20)
    private String subStatus = STATUS_NONE;

    @Column(name = "pilot_ends_at")
    private Instant pilotEndsAt;

    @Column(name = "class_limit", nullable = false)
    private int classLimit = 0;

    @Column(name = "class_student_cap", nullable = false)
    private int classStudentCap = 20;

    @Column(name = "stripe_customer_ref", length = 120)
    private String stripeCustomerRef;

    @Column(name = "terms_accepted_at")
    private Instant termsAcceptedAt;

    @Column(name = "terms_accepted_by", length = 36)
    private String termsAcceptedBy;
}
