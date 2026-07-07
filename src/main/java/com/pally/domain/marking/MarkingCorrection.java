package com.pally.domain.marking;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * A captured teacher marking correction — the WRITE signal of the marking
 * feedback loop. Records the delta between what the AI drafted and what the
 * teacher actually released, so the marking assistant can later learn how THIS
 * teacher marks (mirrors the student weakness signal: a cheap immediate write,
 * compiled later into grounding).
 *
 * <p>Only a SUBSTANTIVE delta is ever captured (see {@code MarkingCorrectionCaptureService}).
 * {@code compiledAt} is null until Part 3 ingests it into the marking-wiki.
 */
public record MarkingCorrection(
        String id,
        String submissionId,
        String classId,
        String subject,
        String aiSuggestedGrade,
        String teacherGrade,
        String aiFeedback,
        String teacherFeedback,
        Instant capturedAt,
        Instant compiledAt) {

    /** A freshly captured (not-yet-compiled) correction. */
    public static MarkingCorrection capture(
            String submissionId, String classId, String subject,
            String aiSuggestedGrade, String teacherGrade,
            String aiFeedback, String teacherFeedback) {
        return new MarkingCorrection(
                IdGenerator.newId(), submissionId, classId, subject,
                aiSuggestedGrade, teacherGrade, aiFeedback, teacherFeedback,
                Instant.now(), null);
    }
}
