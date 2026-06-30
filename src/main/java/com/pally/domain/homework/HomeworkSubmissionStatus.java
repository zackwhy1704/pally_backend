package com.pally.domain.homework;

/**
 * Lifecycle of a homework submission. The teacher-in-the-loop boundary is the
 * jump to {@link #RELEASED}: only then does the teacher's feedback/grade reach
 * the student. AI work never advances a submission past {@link #AI_DRAFTED}.
 */
public enum HomeworkSubmissionStatus {
    /** Work uploaded; OCR/extraction done; awaiting the teacher. */
    SUBMITTED,
    /** AI first-pass draft generated (teacher-only). Still not released. */
    AI_DRAFTED,
    /** Teacher has saved (in-progress) feedback/grade but not released. */
    TEACHER_REVIEWING,
    /** Teacher released their feedback/grade — now visible to the student. */
    RELEASED,
    /** Teacher sent it back for the student to redo. */
    RETURNED
}
