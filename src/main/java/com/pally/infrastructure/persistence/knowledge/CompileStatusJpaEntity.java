package com.pally.infrastructure.persistence.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Durable per-avatar compile status (C2) — the latest compile outcome, so
 *  GET /wiki/compile/status is correct across replicas. */
@Entity
@Table(name = "compile_status")
@Getter
@Setter
public class CompileStatusJpaEntity {

    @Id
    @Column(name = "avatar_id", length = 36)
    private String avatarId;

    @Column(nullable = false, length = 20)
    private String state;

    @Column(name = "pages_compiled", nullable = false)
    private int pagesCompiled;

    @Column(name = "pages_total", nullable = false)
    private int pagesTotal;

    @Column(name = "pages_failed", nullable = false)
    private int pagesFailed;

    /** JSON array of {slug, reason}; null when no failures. */
    @Column(name = "failed_pages", columnDefinition = "TEXT")
    private String failedPages;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
