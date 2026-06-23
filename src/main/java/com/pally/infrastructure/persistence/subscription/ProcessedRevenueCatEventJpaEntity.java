package com.pally.infrastructure.persistence.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * RevenueCat webhook idempotency. PK on the RC event id makes a re-delivery a
 * fast unique-violation the handler catches and short-circuits to 200, so
 * duplicate events never double-apply an entitlement. Mirrors
 * {@link ProcessedStripeEventJpaEntity}.
 */
@Entity
@Table(name = "processed_revenuecat_events")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedRevenueCatEventJpaEntity {

    @Id
    @Column(name = "event_id", length = 80)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();
}
