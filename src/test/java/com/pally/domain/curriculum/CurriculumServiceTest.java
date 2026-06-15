package com.pally.domain.curriculum;

import com.pally.domain.curriculum.port.SyllabusParserPort;
import com.pally.domain.curriculum.port.SyllabusPdfPort;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CurriculumService}.
 * No Spring context — just Mockito stubs and pure domain logic assertions.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurriculumServiceTest {

    @Mock CurriculumRepository      curriculumRepository;
    @Mock CurriculumTopicRepository topicRepository;
    @Mock AvatarCurriculumPort      avatarCurriculumPort;
    @Mock SyllabusPdfPort           pdfPort;
    @Mock SyllabusParserPort        parserPort;

    @InjectMocks CurriculumService service;

    private static final String USER_ID      = "user-1";
    private static final String OTHER_USER   = "user-2";
    private static final String CURRICULUM_ID = "curr-1";
    private static final String AVATAR_ID    = "avatar-1";

    private Curriculum curriculum(String id, String owner) {
        return new Curriculum(id, "Math Syllabus", "MATH", "Grade 7",
                owner, false, Instant.now());
    }

    private Curriculum defaultCurriculum(String id) {
        return new Curriculum(id, "Default Math", "MATH", null,
                null, true, Instant.now());
    }

    // ══════════════════════════════════════════════════════════════════════
    // listCurricula
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void listCurricula_returnsMappedSummaries() {
        List<Curriculum> rows = List.of(
                curriculum("c-1", USER_ID),
                curriculum("c-2", USER_ID)
        );
        when(curriculumRepository.findVisibleToUser(USER_ID, "MATH")).thenReturn(rows);

        Map<String, Object> result = service.listCurricula(USER_ID, "MATH");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> curricula = (List<Map<String, Object>>) result.get("curricula");
        assertThat(curricula).hasSize(2);
        assertThat(curricula.get(0).get("id")).isEqualTo("c-1");
        assertThat(curricula.get(1).get("id")).isEqualTo("c-2");
    }

    @Test
    void listCurricula_emptyResult_returnsEmptyList() {
        when(curriculumRepository.findVisibleToUser(USER_ID, null)).thenReturn(List.of());

        Map<String, Object> result = service.listCurricula(USER_ID, null);

        @SuppressWarnings("unchecked")
        List<?> curricula = (List<?>) result.get("curricula");
        assertThat(curricula).isEmpty();
    }

    // ══════════════════════════════════════════════════════════════════════
    // getTopics
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void getTopics_ownedCurriculum_returnsTopicsAndSummary() {
        Curriculum c = curriculum(CURRICULUM_ID, USER_ID);
        List<CurriculumTopic> topics = List.of(
                new CurriculumTopic("t-1", CURRICULUM_ID, "Algebra", "algebra", 0, Instant.now()),
                new CurriculumTopic("t-2", CURRICULUM_ID, "Geometry", "geometry", 1, Instant.now())
        );
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.of(c));
        when(topicRepository.findByCurriculumIdOrderBySequenceAsc(CURRICULUM_ID))
                .thenReturn(topics);

        Map<String, Object> result = service.getTopics(USER_ID, CURRICULUM_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topicList = (List<Map<String, Object>>) result.get("topics");
        assertThat(topicList).hasSize(2);
        assertThat(topicList.get(0).get("name")).isEqualTo("Algebra");
        assertThat(topicList.get(1).get("slug")).isEqualTo("geometry");
        assertThat(result.get("id")).isEqualTo(CURRICULUM_ID);
    }

    @Test
    void getTopics_defaultCurriculum_noOwnerCheck_allowed() {
        Curriculum c = defaultCurriculum(CURRICULUM_ID);
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.of(c));
        when(topicRepository.findByCurriculumIdOrderBySequenceAsc(CURRICULUM_ID))
                .thenReturn(List.of());

        // ownerUserId is null → any user may read
        Map<String, Object> result = service.getTopics(OTHER_USER, CURRICULUM_ID);

        assertThat(result.get("id")).isEqualTo(CURRICULUM_ID);
    }

    @Test
    void getTopics_curriculumNotFound_throws404() {
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTopics(USER_ID, CURRICULUM_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Curriculum not found");
    }

    @Test
    void getTopics_curriculumOwnedByOtherUser_throws403() {
        Curriculum c = curriculum(CURRICULUM_ID, OTHER_USER);
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.getTopics(USER_ID, CURRICULUM_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Forbidden");
    }

    // ══════════════════════════════════════════════════════════════════════
    // uploadSyllabus
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void uploadSyllabus_withRawText_parsesSavesAndReturnsSummary() {
        List<CurriculumService.ParsedTopic> parsed = List.of(
                new CurriculumService.ParsedTopic("Algebra", "algebra"),
                new CurriculumService.ParsedTopic("Trigonometry", "trigonometry")
        );
        when(parserPort.parse(eq("MATH"), anyString())).thenReturn(parsed);
        when(curriculumRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(topicRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.uploadSyllabus(
                USER_ID, null, "Chapter 1: Algebra\nChapter 2: Trig",
                "MATH", "Grade 9", "My Syllabus");

        assertThat(result.get("topicCount")).isEqualTo(2);
        assertThat(result.get("name")).isEqualTo("My Syllabus");
        assertThat(result.get("subject")).isEqualTo("MATH");
        assertThat(result.get("ownerUserId")).isEqualTo(USER_ID);
        verify(topicRepository, times(2)).save(any());
    }

    @Test
    void uploadSyllabus_withPdfStream_extractsTextThenParses() throws Exception {
        InputStream pdfStream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        when(pdfPort.extractText(pdfStream)).thenReturn("Extracted syllabus text");
        List<CurriculumService.ParsedTopic> parsed = List.of(
                new CurriculumService.ParsedTopic("Sets", "sets")
        );
        when(parserPort.parse(eq("MATH"), anyString())).thenReturn(parsed);
        when(curriculumRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(topicRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.uploadSyllabus(
                USER_ID, pdfStream, null, "MATH", null, null);

        assertThat(result.get("topicCount")).isEqualTo(1);
        verify(pdfPort).extractText(pdfStream);
    }

    @Test
    void uploadSyllabus_noNameProvided_generatesDefaultNameFromSubjectAndGrade() {
        when(parserPort.parse(anyString(), anyString())).thenReturn(
                List.of(new CurriculumService.ParsedTopic("Topic", "topic")));
        when(curriculumRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(topicRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.uploadSyllabus(
                USER_ID, null, "some text", "MATH", "G10", null);

        assertThat(result.get("name").toString()).contains("MATH").contains("G10");
    }

    @Test
    void uploadSyllabus_noNameNoGrade_generatesDefaultNameFromSubjectOnly() {
        when(parserPort.parse(anyString(), anyString())).thenReturn(
                List.of(new CurriculumService.ParsedTopic("Topic", "topic")));
        when(curriculumRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(topicRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.uploadSyllabus(
                USER_ID, null, "some text", "MATH", null, null);

        assertThat(result.get("name").toString()).contains("MATH");
    }

    @Test
    void uploadSyllabus_topicsStoredInSequenceOrder() {
        List<CurriculumService.ParsedTopic> parsed = List.of(
                new CurriculumService.ParsedTopic("A", "a"),
                new CurriculumService.ParsedTopic("B", "b"),
                new CurriculumService.ParsedTopic("C", "c")
        );
        when(parserPort.parse(anyString(), anyString())).thenReturn(parsed);
        when(curriculumRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(topicRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.uploadSyllabus(USER_ID, null, "text", "MATH", null, "Syllabus");

        ArgumentCaptor<CurriculumTopic> captor = ArgumentCaptor.forClass(CurriculumTopic.class);
        verify(topicRepository, times(3)).save(captor.capture());
        List<CurriculumTopic> saved = captor.getAllValues();
        assertThat(saved.get(0).sequence()).isEqualTo(0);
        assertThat(saved.get(1).sequence()).isEqualTo(1);
        assertThat(saved.get(2).sequence()).isEqualTo(2);
    }

    @Test
    void uploadSyllabus_missingSubject_throws400() {
        assertThatThrownBy(() ->
                service.uploadSyllabus(USER_ID, null, "text", null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("subject is required");
    }

    @Test
    void uploadSyllabus_blankSubject_throws400() {
        assertThatThrownBy(() ->
                service.uploadSyllabus(USER_ID, null, "text", "  ", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("subject is required");
    }

    @Test
    void uploadSyllabus_noTextNoPdf_throws400() {
        assertThatThrownBy(() ->
                service.uploadSyllabus(USER_ID, null, null, "MATH", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Provide either a syllabus file or text");
    }

    @Test
    void uploadSyllabus_blankTextNoPdf_throws400() {
        assertThatThrownBy(() ->
                service.uploadSyllabus(USER_ID, null, "  ", "MATH", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Provide either a syllabus file or text");
    }

    @Test
    void uploadSyllabus_pdfExtractionFails_throws400() throws Exception {
        InputStream pdfStream = new ByteArrayInputStream(new byte[]{1});
        when(pdfPort.extractText(pdfStream)).thenThrow(new RuntimeException("PDF parse error"));

        assertThatThrownBy(() ->
                service.uploadSyllabus(USER_ID, pdfStream, null, "MATH", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Could not read");
    }

    @Test
    void uploadSyllabus_parserReturnsNoTopics_throws422() {
        when(parserPort.parse(anyString(), anyString())).thenReturn(List.of());

        assertThatThrownBy(() ->
                service.uploadSyllabus(USER_ID, null, "text", "MATH", null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Could not detect any topics");
    }

    // ══════════════════════════════════════════════════════════════════════
    // attachToAvatar
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void attachToAvatar_validOwnerAndCurriculum_attachesAndReturnsResult() {
        Curriculum c = curriculum(CURRICULUM_ID, USER_ID);
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.of(USER_ID));
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.of(c));
        when(avatarCurriculumPort.attachCurriculum(AVATAR_ID, CURRICULUM_ID))
                .thenReturn(CURRICULUM_ID);

        Map<String, Object> result = service.attachToAvatar(USER_ID, AVATAR_ID, CURRICULUM_ID);

        assertThat(result.get("avatarId")).isEqualTo(AVATAR_ID);
        assertThat(result.get("curriculumId")).isEqualTo(CURRICULUM_ID);
        verify(avatarCurriculumPort).attachCurriculum(AVATAR_ID, CURRICULUM_ID);
    }

    @Test
    void attachToAvatar_detachBySendingNullCurriculumId_callsWithNull() {
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.of(USER_ID));
        when(avatarCurriculumPort.attachCurriculum(eq(AVATAR_ID), eq(null))).thenReturn(null);

        Map<String, Object> result = service.attachToAvatar(USER_ID, AVATAR_ID, null);

        assertThat(result.get("curriculumId")).isEqualTo("");
        verify(avatarCurriculumPort).attachCurriculum(AVATAR_ID, null);
        verify(curriculumRepository, never()).findById(anyString());
    }

    @Test
    void attachToAvatar_detachByBlankCurriculumId_callsWithNull() {
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.of(USER_ID));
        when(avatarCurriculumPort.attachCurriculum(eq(AVATAR_ID), eq(null))).thenReturn(null);

        service.attachToAvatar(USER_ID, AVATAR_ID, "  ");

        verify(avatarCurriculumPort).attachCurriculum(AVATAR_ID, null);
    }

    @Test
    void attachToAvatar_avatarNotFound_throws404() {
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attachToAvatar(USER_ID, AVATAR_ID, CURRICULUM_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Avatar not found");
    }

    @Test
    void attachToAvatar_avatarOwnedByOtherUser_throws403() {
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.of(OTHER_USER));

        assertThatThrownBy(() -> service.attachToAvatar(USER_ID, AVATAR_ID, CURRICULUM_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Forbidden");
    }

    @Test
    void attachToAvatar_curriculumNotFound_throws404() {
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.of(USER_ID));
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.attachToAvatar(USER_ID, AVATAR_ID, CURRICULUM_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Curriculum not found");
    }

    @Test
    void attachToAvatar_curriculumOwnedByOtherUser_throws403() {
        Curriculum c = curriculum(CURRICULUM_ID, OTHER_USER);
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.of(USER_ID));
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> service.attachToAvatar(USER_ID, AVATAR_ID, CURRICULUM_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Forbidden");
    }

    @Test
    void attachToAvatar_defaultCurriculumNullOwner_attachesSuccessfully() {
        // Default curricula have null ownerUserId — any user can attach them
        Curriculum defaultC = defaultCurriculum(CURRICULUM_ID);
        when(avatarCurriculumPort.findOwnerUserId(AVATAR_ID)).thenReturn(Optional.of(USER_ID));
        when(curriculumRepository.findById(CURRICULUM_ID)).thenReturn(Optional.of(defaultC));
        when(avatarCurriculumPort.attachCurriculum(AVATAR_ID, CURRICULUM_ID))
                .thenReturn(CURRICULUM_ID);

        Map<String, Object> result = service.attachToAvatar(USER_ID, AVATAR_ID, CURRICULUM_ID);

        assertThat(result.get("curriculumId")).isEqualTo(CURRICULUM_ID);
    }
}
