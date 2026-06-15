package com.pally.domain.module;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Domain type mirroring {@code ModuleNarrationJpaEntity}.
 * No JPA annotations — persistence is handled by the adapter.
 */
@Getter
@Setter
@NoArgsConstructor
public class ModuleNarration {

    private String id;
    private String moduleId;
    private String voiceId;
    private String segmentsJson;
    private int totalDurationMs;
    private String status;
    private Instant createdAt;
}
