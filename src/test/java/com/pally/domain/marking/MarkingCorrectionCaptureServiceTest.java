package com.pally.domain.marking;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.homework.HomeworkSubmission;
import com.pally.domain.homework.HomeworkSubmissionStatus;
import com.pally.domain.homework.SubmissionFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The WRITE side of the marking loop. Two load-bearing invariants:
 *   1. captures ONLY a SUBSTANTIVE correction (a grade change or a feedback
 *      REDIRECT) — a cosmetic edit captures nothing, or the loop learns noise;
 *   2. NEVER throws — a capture failure must not break the teacher's release.
 */
@ExtendWith(MockitoExtension.class)
class MarkingCorrectionCaptureServiceTest {

    @Mock MarkingCorrectionRepository repository;
    @Mock org.springframework.context.ApplicationEventPublisher events;
    private final ObjectMapper mapper = new ObjectMapper();

    private MarkingCorrectionCaptureService service() {
        return new MarkingCorrectionCaptureService(repository, mapper, events, 15);
    }

    private String draft(String suggestedGrade, String feedback) {
        return "{\"suggestedGrade\":\"" + suggestedGrade + "\",\"criteria\":[],\"feedback\":\""
                + feedback + "\"}";
    }

    /** A released submission with a given AI draft + teacher final. */
    private HomeworkSubmission released(String draftJson, String teacherGrade, String teacherFeedback) {
        HomeworkSubmission s = HomeworkSubmission.reconstitute(
                "sub-1", "class-1", "student-1", "Fractions HW", "MATHS",
                List.of(new SubmissionFile("k", "w.jpg", "image/jpeg", 3)),
                "student work text", HomeworkSubmissionStatus.RELEASED,
                draftJson, Instant.now(), teacherFeedback, teacherGrade,
                Instant.now(), Instant.now(), Instant.now());
        return s;
    }

    @Test
    void gradeChanged_capturesTheCorrectionWithTheDelta() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        service().captureOnRelease(released(draft("A", "Great work"), "C", "Great work"));

        ArgumentCaptor<MarkingCorrection> cap = ArgumentCaptor.forClass(MarkingCorrection.class);
        verify(repository).save(cap.capture());
        MarkingCorrection c = cap.getValue();
        org.assertj.core.api.Assertions.assertThat(c.aiSuggestedGrade()).isEqualTo("A");
        org.assertj.core.api.Assertions.assertThat(c.teacherGrade()).isEqualTo("C");
        org.assertj.core.api.Assertions.assertThat(c.compiledAt()).isNull();
    }

    @Test
    void feedbackRedirect_sameGrade_capturesCorrection() {
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        service().captureOnRelease(released(
                draft("B", "Correct."),
                "B", "Wrong — you divided instead of multiplied."));
        verify(repository).save(any());
    }

    @Test
    void cosmeticFeedbackEdit_sameGrade_capturesNOTHING() {
        // "Good" -> "Good work!" is embellishment, not a correction. THE guard.
        service().captureOnRelease(released(draft("B", "Good"), "B", "Good work!"));
        verify(repository, never()).save(any());
    }

    @Test
    void identicalDraftAndFinal_capturesNothing() {
        service().captureOnRelease(released(draft("B", "Great work"), "B", "Great work"));
        verify(repository, never()).save(any());
    }

    @Test
    void noAiDraft_capturesNothing() {
        // Teacher marked manually (no AI draft) — nothing was suggested to correct.
        service().captureOnRelease(released(null, "A", "Nice job"));
        verify(repository, never()).save(any());
    }

    // ── Audit fix 1: AI-silent is NOT a correction (needs two opinions) ────────────
    @Test
    void aiSuggestedNoGrade_teacherGrades_capturesNothing() {
        // Partial/failed draft: AI produced no grade. The teacher assigning one is
        // primary grading, not correcting a judgment — no delta to learn.
        service().captureOnRelease(released(draft("", "Good"), "A", "Good"));
        verify(repository, never()).save(any());
    }

    @Test
    void aiWroteNoFeedback_teacherWritesFeedback_sameGrade_capturesNothing() {
        // AI gave a grade the teacher kept, but no feedback. The teacher's feedback
        // isn't redirecting an AI opinion that never existed.
        service().captureOnRelease(released(draft("B", ""), "B", "Check your working."));
        verify(repository, never()).save(any());
    }

    @Test
    void aiDraftEntirelyEmptyFields_capturesNothing() {
        service().captureOnRelease(released(draft("", ""), "A", "Nice job"));
        verify(repository, never()).save(any());
    }

    // ── Audit fix 2: a teacher TRIM of the AI's feedback is a redirect, not dropped ─
    @Test
    void teacherTrimsAiFeedback_droppingTheJudgment_isCapturedNotSilentlyDropped() {
        // AI: "Wrong, you divided instead of multiplied here"; teacher softens to
        // "you divided instead of multiplied" (drops "Wrong"). Same grade. The old
        // symmetric containment check wrongly treated this as embellishment.
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        service().captureOnRelease(released(
                draft("B", "Wrong, you divided instead of multiplied here"),
                "B", "you divided instead of multiplied"));
        verify(repository).save(any());
    }

    @Test
    void captureFailure_neverThrows_soReleaseIsNeverBlocked() {
        when(repository.save(any())).thenThrow(new RuntimeException("db down"));
        // A substantive change would try to save — but the throw must be swallowed.
        assertThatCode(() ->
                service().captureOnRelease(released(draft("A", "ok"), "F", "ok")))
                .doesNotThrowAnyException();
    }
}
