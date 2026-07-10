package com.pally.infrastructure.persistence.module;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "module_content_item")
@Getter
@Setter
@NoArgsConstructor
public class ModuleContentItemJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "module_id", nullable = false, length = 36)
    private String moduleId;

    @Column(nullable = false, length = 20)
    private String stage;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(name = "content_json", nullable = false, columnDefinition = "TEXT")
    private String contentJson;

    @Column(name = "answer_json", columnDefinition = "TEXT")
    private String answerJson;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "tier_required", nullable = false, length = 20)
    private String tierRequired;

    /** Groundedness gate (B3) flag payload, or null when clean. */
    @Column(name = "verification_json", columnDefinition = "TEXT")
    private String verificationJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Publishing status: DRAFT, APPROVED, LIVE, ARCHIVED (personal avatars default LIVE),
     * plus the content-health-reaper terminals QUARANTINED / RETIRED. Only LIVE + APPROVED
     * are servable (ModuleContentItemRepository.SERVABLE_STATUSES).
     */
    @Column(nullable = false, length = 20)
    private String status = "LIVE";

    /** Content-health reaper: count of regeneration attempts (2 → RETIRED). */
    @Column(name = "reap_attempts", nullable = false)
    private int reapAttempts;

    /** Content-health reaper: last reap-attempt time (backoff, anti-starvation). Null = never. */
    @Column(name = "reap_last_attempt_at")
    private Instant reapLastAttemptAt;
}
