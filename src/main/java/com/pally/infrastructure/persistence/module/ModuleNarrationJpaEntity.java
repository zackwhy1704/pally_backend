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
@Table(name = "module_narration")
@Getter
@Setter
@NoArgsConstructor
public class ModuleNarrationJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "module_id", nullable = false, length = 36)
    private String moduleId;

    @Column(name = "voice_id", nullable = false, length = 100)
    private String voiceId;

    @Column(name = "segments_json", nullable = false, columnDefinition = "TEXT")
    private String segmentsJson;

    @Column(name = "total_duration_ms", nullable = false)
    private int totalDurationMs;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
