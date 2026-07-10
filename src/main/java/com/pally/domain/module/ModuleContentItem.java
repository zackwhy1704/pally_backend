package com.pally.domain.module;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Domain type mirroring {@code ModuleContentItemJpaEntity}.
 * No JPA annotations — persistence is handled by the adapter.
 */
@Getter
@Setter
@NoArgsConstructor
public class ModuleContentItem {

    private String id;
    private String moduleId;
    private String stage;
    private String type;
    private String contentJson;
    private String answerJson;
    private int sortOrder;
    private String tierRequired;
    private Instant createdAt;
    private String status = "LIVE";
    /** Groundedness gate (B3) flag payload, or null when clean. */
    private String verificationJson;
    /** Content-health reaper: regeneration attempts (2 → RETIRED). */
    private int reapAttempts;
    /** Content-health reaper: last scan/reap-attempt time (scan cursor + regen backoff). */
    private Instant reapLastAttemptAt;
}
