package com.pally.infrastructure.persistence.centre;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "class_brief")
@Getter
@Setter
@NoArgsConstructor
public class ClassBriefJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "module_id", length = 36)
    private String moduleId;

    @Column(name = "brief_json", nullable = false, columnDefinition = "TEXT")
    private String briefJson;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;
}
