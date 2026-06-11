package com.pally.infrastructure.persistence.module;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "module_progress")
@Getter
@Setter
@NoArgsConstructor
public class ModuleProgressJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "module_id", nullable = false, length = 36)
    private String moduleId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "item_id", nullable = false, length = 36)
    private String itemId;

    @Column(nullable = false, length = 20)
    private String stage;

    @Column(name = "response_json", columnDefinition = "TEXT")
    private String responseJson;

    @Column
    private BigDecimal score;

    @Column(name = "target_concept", length = 255)
    private String targetConcept;

    @Column(name = "completed_at")
    private Instant completedAt;
}
