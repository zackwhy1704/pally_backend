package com.pally.domain.homework;

import com.pally.domain.centre.OrgClassRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.StoragePort;
import com.pally.infrastructure.push.FcmService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The teacher-in-the-loop invariants are the point of this feature, so the tests
 * assert them in plain English: AI only ever DRAFTS, an AI failure changes
 * nothing, and nothing reaches the student without non-blank teacher feedback.
 */
@ExtendWith(MockitoExtension.class)
class HomeworkSubmissionServiceTest {

    @Mock private HomeworkSubmissionRepository submissionRepository;
    @Mock private StoragePort storagePort;
    @Mock private DocumentTextExtractionPort textExtractor;
    @Mock private HomeworkFeedbackGenerator feedbackGenerator;
    @Mock private OrgClassRepository orgClassRepository;
    @Mock private WikiRepository wikiRepository;
    @Mock private FcmService fcmService;
    @Mock private com.pally.domain.marking.MarkingCorpusService markingCorpusService;

    private HomeworkSubmissionService service;

    @BeforeEach
    void setUp() {
        service = new HomeworkSubmissionService(
                submissionRepository, storagePort, textExtractor, feedbackGenerator,
                orgClassRepository, wikiRepository, fcmService, markingCorpusService);
        // save() echoes its argument back, like a real repository round-trip.
        // Lenient: the validation/failure tests deliberately never reach save().
        lenient().when(submissionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    private static IncomingFile file() {
        return new IncomingFile("work.jpg", "image/jpeg", new byte[]{1, 2, 3});
    }

    // ── create ──────────────────────────────────────────────────────────────

    @Test
    void createSubmission_storesArtifactExtractsTextAndPersistsAsSubmitted() {
        when(textExtractor.extract(any(), anyString())).thenReturn("2 + 2 = 5");

        HomeworkSubmission saved = service.createSubmission(
                "class-1", "stud-1", "Maths WS3", "Maths", List.of(file()));

        verify(storagePort).upload(anyString(), any(), anyString());
        assertThat(saved.getStatus()).isEqualTo(HomeworkSubmissionStatus.SUBMITTED);
        assertThat(saved.getExtractedText()).contains("2 + 2 = 5");
        assertThat(saved.getFiles()).hasSize(1);
        assertThat(saved.getStudentId()).isEqualTo("stud-1");
    }

    @Test
    void createSubmission_rejectsBlankTitle() {
        assertThatThrownBy(() -> service.createSubmission(
                "class-1", "stud-1", "  ", "Maths", List.of(file())))
                .isInstanceOf(BusinessException.class);
        verify(submissionRepository, never()).save(any());
    }

    @Test
    void createSubmission_rejectsNoFiles() {
        assertThatThrownBy(() -> service.createSubmission(
                "class-1", "stud-1", "Maths WS3", "Maths", List.of()))
                .isInstanceOf(BusinessException.class);
        verify(submissionRepository, never()).save(any());
    }

    // ── AI draft (teacher-in-the-loop) ────────────────────────────────────────

    @Test
    void generateAiDraft_setsAiDraftedAndNeverReleased() {
        HomeworkSubmission submitted = HomeworkSubmission.create(
                "class-1", "stud-1", "Maths WS3", "Maths", List.of(), "2 + 2 = 5");
        when(submissionRepository.findById("s1")).thenReturn(Optional.of(submitted));
        when(orgClassRepository.findCorpusAvatarIdByClassId("class-1")).thenReturn(Optional.empty());
        when(feedbackGenerator.generateDraftJson(any(), any(), any(), any()))
                .thenReturn("{\"feedback\":\"good start\"}");

        HomeworkSubmission result = service.generateAiDraft("s1");

        assertThat(result.getStatus()).isEqualTo(HomeworkSubmissionStatus.AI_DRAFTED);
        assertThat(result.isReleased()).isFalse();
        assertThat(result.getAiDraftFeedbackJson()).contains("good start");
        assertThat(result.getTeacherFeedback()).isNull();
    }

    @Test
    void generateAiDraft_grounding_putsCompiledMarkingStandardBeforeClassMaterial() {
        HomeworkSubmission submitted = HomeworkSubmission.create(
                "class-1", "stud-1", "Maths WS3", "Maths", List.of(), "2 + 2 = 5");
        when(submissionRepository.findById("s1")).thenReturn(Optional.of(submitted));
        // Build page mocks first (each stubs internally — never nest inside when()).
        var markPage = page("How Marks Are Awarded",
                "Award the method mark even if the answer is wrong.");
        var classPage = page("Fractions", "A fraction is part of a whole.");
        // Compiled marking standard (authority) for this class's (org, subject).
        when(markingCorpusService.findAvatarIdForClass("class-1")).thenReturn(Optional.of("mark-av"));
        when(wikiRepository.findActiveByAvatarId("mark-av")).thenReturn(List.of(markPage));
        // Class content brain (background).
        when(orgClassRepository.findCorpusAvatarIdByClassId("class-1")).thenReturn(Optional.of("class-av"));
        when(wikiRepository.findActiveByAvatarId("class-av")).thenReturn(List.of(classPage));

        ArgumentCaptor<String> grounding = ArgumentCaptor.forClass(String.class);
        when(feedbackGenerator.generateDraftJson(grounding.capture(), any(), any(), any()))
                .thenReturn("{\"feedback\":\"ok\"}");

        service.generateAiDraft("s1");

        String g = grounding.getValue();
        assertThat(g).contains("MARKING STANDARD").contains("How Marks Are Awarded")
                .contains("CLASS MATERIAL").contains("Fractions");
        // The marking standard leads; class material is background.
        assertThat(g.indexOf("MARKING STANDARD")).isLessThan(g.indexOf("CLASS MATERIAL"));
        assertThat(g.indexOf("Award the method mark"))
                .isLessThan(g.indexOf("A fraction is part"));
    }

    // Real WikiPage (not a mock) so groundingText() — which prepends any context
    // to the content — runs for real in the grounding assembly.
    private com.pally.domain.knowledge.WikiPage page(String title, String content) {
        return com.pally.domain.knowledge.WikiPage.create(
                "av", title.toLowerCase().replace(' ', '-'), title, content);
    }

    @Test
    void generateAiDraft_onAiFailureThrows503AndLeavesSubmissionUntouched() {
        HomeworkSubmission submitted = HomeworkSubmission.create(
                "class-1", "stud-1", "Maths WS3", "Maths", List.of(), "2 + 2 = 5");
        when(submissionRepository.findById("s1")).thenReturn(Optional.of(submitted));
        when(orgClassRepository.findCorpusAvatarIdByClassId("class-1")).thenReturn(Optional.empty());
        when(feedbackGenerator.generateDraftJson(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("model down"));

        assertThatThrownBy(() -> service.generateAiDraft("s1"))
                .isInstanceOf(BusinessException.class);

        verify(submissionRepository, never()).save(any());
        assertThat(submitted.getStatus()).isEqualTo(HomeworkSubmissionStatus.SUBMITTED);
    }

    // ── release (human sign-off invariant) ────────────────────────────────────

    @Test
    void release_rejectsWhenTeacherFeedbackIsBlank() {
        HomeworkSubmission submitted = HomeworkSubmission.create(
                "class-1", "stud-1", "Maths WS3", "Maths", List.of(), "");
        when(submissionRepository.findById("s1")).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.release("s1"))
                .isInstanceOf(BusinessException.class);

        assertThat(submitted.getStatus()).isNotEqualTo(HomeworkSubmissionStatus.RELEASED);
        verify(fcmService, never()).sendToUser(anyString(), anyString(), anyString(), any());
    }

    @Test
    void release_withFeedbackReleasesAndNotifiesStudentWithNoPaymentContent() {
        HomeworkSubmission submitted = HomeworkSubmission.create(
                "class-1", "stud-1", "Maths WS3", "Maths", List.of(), "");
        when(submissionRepository.findById("s1")).thenReturn(Optional.of(submitted));
        when(fcmService.isConfigured()).thenReturn(true);

        service.saveTeacherReview("s1", "Great working, watch your carry.", "B+");
        HomeworkSubmission released = service.release("s1");

        assertThat(released.getStatus()).isEqualTo(HomeworkSubmissionStatus.RELEASED);
        assertThat(released.getReleasedAt()).isNotNull();

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(fcmService).sendToUser(anyString(), anyString(), body.capture(), any());
        assertThat(body.getValue().toLowerCase())
                .doesNotContain("http").doesNotContain("upgrade").doesNotContain("pay");
    }

    @Test
    void get_whenMissingThrowsNotFound() {
        when(submissionRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(BusinessException.class);
    }
}
