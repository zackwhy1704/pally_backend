package com.pally.infrastructure.persistence.subscription;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class SubscriptionJpaEntity {

    @Id
    @Column(name = "user_id", length = 36, nullable = false)
    private String userId;

    @Column(name = "stripe_customer_id", length = 80)
    private String stripeCustomerId;

    @Column(name = "stripe_subscription_id", length = 80)
    private String stripeSubscriptionId;

    @Column(length = 40)
    private String plan;

    /// Mirrors Stripe: free | trialing | active | past_due | canceled | …
    @Column(nullable = false, length = 20)
    private String status = "free";

    @Column(name = "current_period_end")
    private Instant currentPeriodEnd;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
