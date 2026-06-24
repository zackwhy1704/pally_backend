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

    /**
     * When true, this assignment resolves to a per-student targeted module set at
     * start time (rather than the class-uniform {@link #moduleIds}). See
     * AssignmentService resolution.
     */
    @Column(nullable = false)
    private boolean personalized = false;

    /**
     * Comma-separated wiki page slugs this assignment covers. Null = whole class.
     * Bounds per-student weak-module selection.
     */
    @Column(name = "topic_scope", columnDefinition = "TEXT")
    private String topicScope;

    /**
     * Comma-separated PRIOR-topic wiki slugs the teacher selected to diagnose in a
     * pre-class assignment. Each student's pre-class set adds a diagnostic over the
     * subset of these they are weakest on. Null = no diagnostic.
     */
    @Column(name = "prereq_scope", columnDefinition = "TEXT")
    private String prereqScope;

    @Column(name = "due_date", nullable = false)
    private Instant dueDate;

    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Teacher-authored model answer(s). A JSON blob — typically an object keyed
     * by question/concept, but free text is allowed. Server-withheld from
     * students until {@link #answersReleasedAt} is in the past.
     */
    @Column(name = "model_answer", columnDefinition = "TEXT")
    private String modelAnswer;

    /**
     * The instant model answers become visible to students. Null = never
     * released. The student mapper omits model answers until this is <= now.
     */
    @Column(name = "answers_released_at")
    private Instant answersReleasedAt;

    /** True once {@link #answersReleasedAt} is set and not in the future. */
    public boolean answersReleased() {
        return answersReleasedAt != null && !answersReleasedAt.isAfter(Instant.now());
    }
}
