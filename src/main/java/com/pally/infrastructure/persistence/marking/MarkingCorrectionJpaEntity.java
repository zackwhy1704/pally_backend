package com.pally.infrastructure.persistence.marking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** JPA row for a captured marking correction. Never leaves infrastructure. */
@Entity
@Table(name = "marking_corrections")
@Getter
@Setter
@NoArgsConstructor
public class MarkingCorrectionJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "submission_id", nullable = false, length = 36)
    private String submissionId;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(length = 64)
    private String subject;

    @Column(name = "ai_suggested_grade", length = 64)
    private String aiSuggestedGrade;

    @Column(name = "ai_feedback", columnDefinition = "TEXT")
    private String aiFeedback;

    @Column(name = "teacher_grade", length = 64)
    private String teacherGrade;

    @Column(name = "teacher_feedback", columnDefinition = "TEXT")
    private String teacherFeedback;

    @Column(name = "captured_at", nullable = false)
    private Instant capturedAt;

    @Column(name = "compiled_at")
    private Instant compiledAt;

    @Column(name = "removed_at")
    private Instant removedAt;
}
