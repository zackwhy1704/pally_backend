package com.pally.domain.marking;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.homework.HomeworkSubmission;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
@Slf4j
public class MarkingCorrectionCaptureService {

    private final MarkingCorrectionRepository repository;
    private final ObjectMapper mapper;

    /// If the teacher's feedback merely EXTENDS the AI's by at most this many chars
    /// (added praise/punctuation), it's embellishment, not a redirect — capture
    /// nothing. A tunable dial, not a semantic judgment: raise it to treat longer
    /// additions as embellishment, lower it to capture more. Over-capture isn't
    /// free — it drives Part 3's marking-wiki recompiles — so it's deliberately
    /// conservative and externally tunable without a redeploy.
    private final int embellishMaxChars;

    public MarkingCorrectionCaptureService(
            MarkingCorrectionRepository repository,
            ObjectMapper mapper,
            @Value("${marking.correction.embellish-max-chars:15}") int embellishMaxChars) {
        this.repository = repository;
        this.mapper = mapper;
        this.embellishMaxChars = embellishMaxChars;
    }

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
        // A correction needs TWO opinions that disagree. If the AI suggested no
        // grade (draft failure / partial draft) the teacher isn't CORRECTING a
        // judgment — they're doing the primary grading. No AI opinion → no delta.
        if (ai.isEmpty() || teacher.isEmpty()) return false;
        return !ai.equals(teacher);
    }

    /**
     * The teacher's feedback REDIRECTS the AI's (a real correction) rather than
     * merely embellishing it. Requires two opinions: if the AI wrote no feedback,
     * the teacher's feedback isn't correcting an AI judgment — nothing to redirect.
     * Cosmetic = identical after normalisation, OR the teacher EXTENDED the AI's
     * feedback by ≤ embellishMaxChars (added praise/punctuation). Note the
     * containment is DIRECTIONAL (teacher-extends-AI only): a teacher who TRIMS the
     * AI's feedback (dropping "Wrong…") is softening a judgment — a real redirect,
     * not embellishment — so it is NOT silently dropped.
     */
    private boolean feedbackRedirects(String aiFeedback, String teacherFeedback) {
        String ai = norm(aiFeedback);
        String teacher = norm(teacherFeedback);
        if (ai.isEmpty() || teacher.isEmpty()) return false; // need both opinions
        if (ai.equals(teacher)) return false;                // identical → cosmetic
        if (teacher.contains(ai) && (teacher.length() - ai.length()) <= embellishMaxChars) {
            return false;                                    // teacher merely extended AI → embellishment
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
