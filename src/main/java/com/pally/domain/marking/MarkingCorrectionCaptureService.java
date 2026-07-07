package com.pally.domain.marking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.homework.HomeworkSubmission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Captures a teacher's SUBSTANTIVE marking correction at release — the WRITE side
 * of the marking feedback loop. Called (best-effort) from
 * {@code HomeworkSubmissionService.release()} once the human has signed off.
 *
 * <p>Two invariants:
 * <ul>
 *   <li><b>Never blocks release.</b> {@link #captureOnRelease} catches everything
 *       and returns quietly — a capture bug must never stop a teacher finishing
 *       marking (release is the human-sign-off hot path). Mirrors {@code
 *       notifyReleased}'s best-effort posture.</li>
 *   <li><b>Substantive only.</b> A correction is captured ONLY when the teacher's
 *       final MATERIALLY differs from the AI draft — a real grade change, or
 *       feedback that redirects rather than embellishes. A cosmetic edit ("Good"
 *       → "Good work!") is NOT a correction; capturing it would feed noise into
 *       the marking-wiki and make future drafts worse.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarkingCorrectionCaptureService {

    /// If one feedback string merely EXTENDS the other by at most this many chars,
    /// treat it as embellishment (cosmetic), not a redirection.
    private static final int EMBELLISH_MAX_CHARS = 15;

    private final MarkingCorrectionRepository repository;
    private final ObjectMapper mapper;

    /**
     * Best-effort capture. Never throws. Persists a correction only when the
     * teacher's released grade/feedback is a substantive change from the AI draft.
     */
    public void captureOnRelease(HomeworkSubmission submission) {
        try {
            String draftJson = submission.getAiDraftFeedbackJson();
            if (draftJson == null || draftJson.isBlank()) {
                return; // no AI draft → nothing was suggested to correct
            }
            Map<String, Object> draft = parse(draftJson);
            String aiGrade = str(draft.get("suggestedGrade"));
            String aiFeedback = str(draft.get("feedback"));
            String teacherGrade = submission.getTeacherGrade();
            String teacherFeedback = submission.getTeacherFeedback();

            if (!isSubstantive(aiGrade, teacherGrade, aiFeedback, teacherFeedback)) {
                return; // cosmetic / identical → not a correction, capture nothing
            }

            MarkingCorrection saved = repository.save(MarkingCorrection.capture(
                    submission.getId(), submission.getClassId(), submission.getSubject(),
                    aiGrade, teacherGrade, aiFeedback, teacherFeedback));
            log.info("[Marking] captured correction={} class={} submission={} gradeDelta={}->{}",
                    saved.id(), submission.getClassId(), submission.getId(), aiGrade, teacherGrade);
        } catch (Exception e) {
            // HARD invariant: capture never affects the release.
            log.warn("[Marking] correction capture failed (non-fatal) submission={}: {}",
                    submission.getId(), e.toString());
        }
    }

    /** A substantive correction = the grade changed, OR the feedback redirects. */
    boolean isSubstantive(String aiGrade, String teacherGrade, String aiFeedback, String teacherFeedback) {
        return gradeChanged(aiGrade, teacherGrade)
                || feedbackRedirects(aiFeedback, teacherFeedback);
    }

    private boolean gradeChanged(String aiGrade, String teacherGrade) {
        String ai = norm(aiGrade);
        String teacher = norm(teacherGrade);
        if (teacher.isEmpty()) return false;          // teacher set no grade → no grade signal
        if (ai.isEmpty()) return true;                // AI suggested none, teacher graded → a change
        return !ai.equals(teacher);
    }

    /**
     * The teacher's feedback REDIRECTS the AI's (a real correction) rather than
     * merely embellishing it. Cosmetic: identical after normalisation, or one is
     * the other plus ≤ EMBELLISH_MAX_CHARS of extra text (added praise/punctuation).
     */
    private boolean feedbackRedirects(String aiFeedback, String teacherFeedback) {
        String teacher = norm(teacherFeedback);
        if (teacher.isEmpty()) return false;          // no teacher feedback → no signal here
        String ai = norm(aiFeedback);
        if (ai.isEmpty()) return true;                // teacher wrote substance where AI had none
        if (ai.equals(teacher)) return false;         // identical → cosmetic
        // One contains the other with only a small addition → embellishment, not a redirect.
        String longer = teacher.length() >= ai.length() ? teacher : ai;
        String shorter = teacher.length() >= ai.length() ? ai : teacher;
        if (longer.contains(shorter) && (longer.length() - shorter.length()) <= EMBELLISH_MAX_CHARS) {
            return false;
        }
        return true;
    }

    /** Lowercase, collapse whitespace, strip surrounding punctuation/space. */
    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "")
                .trim();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private Map<String, Object> parse(String json) {
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
