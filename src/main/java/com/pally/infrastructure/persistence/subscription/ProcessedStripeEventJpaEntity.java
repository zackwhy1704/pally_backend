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
 * Item 10.2 — Stripe webhook idempotency. PK on event.id makes a
 * re-delivery a fast unique-violation that the handler catches and
 * short-circuits to 200, so duplicate events never double-credit a
 * subscription.
 */
@Entity
@Table(name = "processed_stripe_events")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedStripeEventJpaEntity {

    @Id
    @Column(name = "event_id", length = 80)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt = Instant.now();
}
