package com.pally.infrastructure.persistence.homework;

import com.pally.domain.homework.HomeworkSubmissionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * JPA row for a homework submission. Stays inside infrastructure — the adapter
 * maps to/from the {@code HomeworkSubmission} domain type at the boundary.
 * {@code filesJson} holds the artifact list as JSON (mapped in the adapter).
 */
@Entity
@Table(
        name = "homework_submission",
        indexes = {
                @Index(name = "idx_homework_submission_class", columnList = "class_id,status"),
                @Index(name = "idx_homework_submission_student", columnList = "student_id,class_id")
        })
@Getter
@Setter
@NoArgsConstructor
public class HomeworkSubmissionJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "class_id", nullable = false, length = 36)
    private String classId;

    @Column(name = "student_id", length = 36)
    private String studentId;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 120)
    private String subject;

    @Column(name = "files_json", nullable = false, columnDefinition = "TEXT")
    private String filesJson;

    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private HomeworkSubmissionStatus status;

    @Column(name = "ai_draft_feedback_json", columnDefinition = "TEXT")
    private String aiDraftFeedbackJson;

    @Column(name = "ai_drafted_at")
    private Instant aiDraftedAt;

    @Column(name = "teacher_feedback", columnDefinition = "TEXT")
    private String teacherFeedback;

    @Column(name = "teacher_grade", length = 60)
    private String teacherGrade;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
