package com.pally.domain.module;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Domain type mirroring {@code ModuleProgressJpaEntity}.
 * No JPA annotations — persistence is handled by the adapter.
 */
@Getter
@Setter
@NoArgsConstructor
public class ModuleProgress {

    private String id;
    private String moduleId;
    private String userId;
    private String itemId;
    private String stage;
    private String responseJson;
    private BigDecimal score;
    private String targetConcept;
    private Instant completedAt;
    /**
     * Trust class of this row's grade. null = legacy (pre-signal-typing, full
     * weight). UNGRADED rows carry score = null so an ungraded item is never a
     * false 0 in mastery.
     */
    private GradingSignal signalType;
}
