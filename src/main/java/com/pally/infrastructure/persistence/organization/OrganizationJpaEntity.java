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
}
