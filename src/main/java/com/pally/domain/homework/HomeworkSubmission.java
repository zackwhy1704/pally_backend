package com.pally.domain.homework;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;
import java.util.List;

/**
 * Domain entity for a homework submission. Pure domain model — no Spring/JPA.
 *
 * <p>State transitions are expressed as intention-revealing mutators
 * ({@link #applyAiDraft}, {@link #applyTeacherReview}, {@link #release},
 * {@link #returnForRedo}) rather than a raw setter, so the teacher-in-the-loop
 * invariants live in one place: AI can only move SUBMITTED -> AI_DRAFTED, and a
 * release requires non-blank teacher feedback.
 */
public final class HomeworkSubmission {

    private final String id;
    private final String classId;
    private final String studentId;
    private final String title;
    private final String subject;
    private final List<SubmissionFile> files;
    private final String extractedText;
    private HomeworkSubmissionStatus status;
    private String aiDraftFeedbackJson;
    private Instant aiDraftedAt;
    private String teacherFeedback;
    private String teacherGrade;
    private Instant releasedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    private HomeworkSubmission(
            String id, String classId, String studentId, String title, String subject,
            List<SubmissionFile> files, String extractedText, HomeworkSubmissionStatus status,
            String aiDraftFeedbackJson, Instant aiDraftedAt, String teacherFeedback,
            String teacherGrade, Instant releasedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.classId = classId;
        this.studentId = studentId;
        this.title = title;
        this.subject = subject;
        this.files = files;
        this.extractedText = extractedText;
        this.status = status;
        this.aiDraftFeedbackJson = aiDraftFeedbackJson;
        this.aiDraftedAt = aiDraftedAt;
        this.teacherFeedback = teacherFeedback;
        this.teacherGrade = teacherGrade;
        this.releasedAt = releasedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** A freshly uploaded submission, in SUBMITTED. */
    public static HomeworkSubmission create(String classId, String studentId, String title,
                                            String subject, List<SubmissionFile> files,
                                            String extractedText) {
        Instant now = Instant.now();
        return new HomeworkSubmission(
                IdGenerator.newId(), classId, studentId, title, subject, files, extractedText,
                HomeworkSubmissionStatus.SUBMITTED, null, null, null, null, null, now, now);
    }

    /** Rehydrate from persistence. */
    public static HomeworkSubmission reconstitute(
            String id, String classId, String studentId, String title, String subject,
            List<SubmissionFile> files, String extractedText, HomeworkSubmissionStatus status,
            String aiDraftFeedbackJson, Instant aiDraftedAt, String teacherFeedback,
            String teacherGrade, Instant releasedAt, Instant createdAt, Instant updatedAt) {
        return new HomeworkSubmission(id, classId, studentId, title, subject, files, extractedText,
                status, aiDraftFeedbackJson, aiDraftedAt, teacherFeedback, teacherGrade,
                releasedAt, createdAt, updatedAt);
    }

    /**
     * Record the AI first-pass draft. NEVER releases — this only moves the
     * submission to AI_DRAFTED (or leaves it past that if the teacher already
     * started reviewing). A re-draft on a RELEASED submission is rejected.
     */
    public void applyAiDraft(String draftJson) {
        if (status == HomeworkSubmissionStatus.RELEASED) {
            throw new IllegalStateException("Cannot re-draft a released submission");
        }
        this.aiDraftFeedbackJson = draftJson;
        this.aiDraftedAt = Instant.now();
        // Don't downgrade a teacher who is mid-review back to AI_DRAFTED.
        if (status == HomeworkSubmissionStatus.SUBMITTED
                || status == HomeworkSubmissionStatus.RETURNED) {
            this.status = HomeworkSubmissionStatus.AI_DRAFTED;
        }
        touch();
    }

    /** Save the teacher's in-progress feedback/grade (not yet released). */
    public void applyTeacherReview(String feedback, String grade) {
        this.teacherFeedback = feedback;
        this.teacherGrade = grade;
        if (status != HomeworkSubmissionStatus.RELEASED) {
            this.status = HomeworkSubmissionStatus.TEACHER_REVIEWING;
        }
        touch();
    }

    /**
     * Release the teacher's feedback to the student. Requires non-blank feedback —
     * the whole point is that a human signed off before anything reached the student.
     */
    public void release() {
        if (teacherFeedback == null || teacherFeedback.isBlank()) {
            throw new IllegalStateException("Cannot release without teacher feedback");
        }
        this.status = HomeworkSubmissionStatus.RELEASED;
        this.releasedAt = Instant.now();
        touch();
    }

    /** Send back for the student to redo, optionally with a note (stored as feedback). */
    public void returnForRedo(String note) {
        if (note != null && !note.isBlank()) {
            this.teacherFeedback = note;
        }
        this.status = HomeworkSubmissionStatus.RETURNED;
        this.releasedAt = null;
        touch();
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean isReleased() {
        return status == HomeworkSubmissionStatus.RELEASED;
    }

    public String getId() { return id; }
    public String getClassId() { return classId; }
    public String getStudentId() { return studentId; }
    public String getTitle() { return title; }
    public String getSubject() { return subject; }
    public List<SubmissionFile> getFiles() { return files; }
    public String getExtractedText() { return extractedText; }
    public HomeworkSubmissionStatus getStatus() { return status; }
    public String getAiDraftFeedbackJson() { return aiDraftFeedbackJson; }
    public Instant getAiDraftedAt() { return aiDraftedAt; }
    public String getTeacherFeedback() { return teacherFeedback; }
    public String getTeacherGrade() { return teacherGrade; }
    public Instant getReleasedAt() { return releasedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
