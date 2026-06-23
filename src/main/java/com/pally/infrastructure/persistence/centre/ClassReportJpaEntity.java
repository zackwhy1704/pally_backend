package com.pally.infrastructure.persistence.centre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Persisted AI class report (one row per class). Replaces the old in-process
 * cache so reports survive Railway redeploys and are shared across instances.
 * {@code status} drives the async generation contract: generating → ready/failed.
 */
@Entity
@Table(name = "class_reports")
@Getter
@Setter
@NoArgsConstructor
public class ClassReportJpaEntity {

    public static final String STATUS_GENERATING = "generating";
    public static final String STATUS_READY = "ready";
    public static final String STATUS_FAILED = "failed";

    @Id
    @Column(name = "class_id", length = 64)
    private String classId;

    @Column(columnDefinition = "TEXT")
    private String narrative;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String error;

    @Column(name = "generated_at")
    private Instant generatedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
