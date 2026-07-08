package com.pally.infrastructure.persistence.cost;

import com.pally.domain.cost.AiCallType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "ai_usage")
@Getter
@Setter
@NoArgsConstructor
public class AiUsageJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "avatar_id", length = 36)
    private String avatarId;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_type", nullable = false, length = 32)
    private AiCallType callType;

    @Column(name = "purpose_label", length = 64)
    private String purposeLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "call_trigger", length = 24)
    private com.pally.domain.cost.AiTrigger trigger;

    @Column(nullable = false)
    private boolean success = true;

    @Column(nullable = false)
    private boolean estimated = false;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(name = "input_tokens", nullable = false)
    private long inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private long outputTokens;

    @Column(name = "est_cost_micros", nullable = false)
    private long estCostMicros;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
