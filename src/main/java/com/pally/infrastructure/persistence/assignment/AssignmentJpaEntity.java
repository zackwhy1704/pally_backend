package com.pally.infrastructure.persistence.assignment;

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
@Table(name = "assignment")
@Getter
@Setter
@NoArgsConstructor
public class AssignmentJpaEntity {

    public static final String TYPE_PRE_CLASS = "PRE_CLASS";
    public static final String TYPE_POST_CLASS = "POST_CLASS";
    public static final String TYPE_REVISION = "REVISION";
    public static final String TYPE_CUSTOM = "CUSTOM";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", length = 36)
    private String classId;

    /// Student ID for parent-assigned revisions (V64). Null for class assignments.
    @Column(name = "student_id", length = 36)
    private String studentId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 20)
    private String type;

    /** Comma-separated module IDs (JSON array string). */
    @Column(name = "module_ids", columnDefinition = "TEXT")
    private String moduleIds;

    /** Comma-separated item IDs (JSON array string). */
    @Column(name = "item_ids", columnDefinition = "TEXT")
    private String itemIds;

    /** Comma-separated stage names, e.g. "LEARN,TEST,PROVE". */
    @Column(columnDefinition = "TEXT")
    private String stages;

    @Column(name = "mastery_threshold")
    private BigDecimal masteryThreshold;

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
