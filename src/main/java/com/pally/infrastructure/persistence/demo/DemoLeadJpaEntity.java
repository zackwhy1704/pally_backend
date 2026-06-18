package com.pally.infrastructure.persistence.demo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persists inbound B2B demo / sales leads (V83 migration).
 * Admin staff progress leads through the status lifecycle via
 * {@code PATCH /api/v1/admin/leads/{id}/status}.
 */
@Entity
@Table(name = "demo_leads")
@Getter
@Setter
@NoArgsConstructor
public class DemoLeadJpaEntity {

    public static final String STATUS_NEW          = "NEW";
    public static final String STATUS_CONTACTED    = "CONTACTED";
    public static final String STATUS_PILOT_ACTIVE = "PILOT_ACTIVE";
    public static final String STATUS_CLOSED_WON   = "CLOSED_WON";
    public static final String STATUS_CLOSED_LOST  = "CLOSED_LOST";
    public static final String STATUS_EXPIRED_LEAD = "EXPIRED";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "org_name", nullable = false, length = 200)
    private String orgName;

    @Column(name = "contact_name", nullable = false, length = 200)
    private String contactName;

    @Column(nullable = false, length = 200)
    private String email;

    @Column(nullable = false, length = 50)
    private String phone;

    @Column(nullable = false, length = 20)
    private String segment = "CENTRE";

    @Column(name = "est_classes")
    private Integer estClasses;

    @Column(name = "est_students")
    private Integer estStudents;

    @Column(name = "org_id", length = 36)
    private String orgId;

    @Column(nullable = false, length = 30)
    private String status = STATUS_NEW;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
