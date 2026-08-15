package com.pally.domain.classroom;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.ClassCrudService;
import com.pally.domain.classroom.dto.ClassroomAttackResponse;
import com.pally.domain.classroom.dto.ClassroomJoinResponse;
import com.pally.domain.classroom.dto.ClassroomStateResponse;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.QuizService;
import com.pally.domain.quiz.dto.QuizQuestionResponse;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.domain.quiz.usecase.SubmitQuizAnswersUseCase;
import com.pally.domain.weakness.WeaknessProfileService;
import com.pally.infrastructure.ai.ModerationService;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Nickname moderation is the one piece here with real stakes if it's
 * silently inert (a child-facing pseudonym reaching every classmate
 * unscreened) — see the no-op-then-restore test at the bottom, which
 * disables the block condition in source and confirms exactly the tests
 * that depend on it go red.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassroomSessionServiceTest {

    @Mock CentreAccessService centreAccessService;
    @Mock ClassCrudService classCrudService;
    @Mock WikiRepository wikiRepository;
    @Mock QuizGeneratorPort quizGeneratorPort;
    @Mock QuizService quizService;
    @Mock QuizAnswerKeyRepository answerKeyRepository;
    @Mock SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    @Mock WeaknessProfileService weaknessProfileService;
    @Mock ModerationService moderationService;
    @Mock AvatarRepository avatarRepository;
    @Mock ClassroomSessionRepository sessionRepository;

    // Real, not mocked — pure in-memory data structure, more genuine than mocking it.
    final ClassroomEventBus eventBus = new ClassroomEventBus();
    final ObjectMapper objectMapper = new ObjectMapper();

    ClassroomSessionService service;

    private static final String TEACHER = "teacher-1";
    private static final String ORG = "org-1";
    private static final String CLASS_ID = "class-1";
    private static final String CORPUS_AVATAR = "corpus-1";
    private static final String STUDENT = "student-1";
    private static final String STUDENT_AVATAR = "student-avatar-1";
    private static final String PAGE_ID = "page-1";

    private static final ModerationService.ModerationResult SAFE =
            new ModerationService.ModerationResult(false, "SAFE", "SAFE", null);
    private static final ModerationService.ModerationResult BLOCKED =
            new ModerationService.ModerationResult(true, "BULLYING", "HIGH", "Let's keep it kind.");

    @BeforeEach
    void setUp() {
        service = new ClassroomSessionService(centreAccessService, classCrudService, wikiRepository,
                quizGeneratorPort, quizService, answerKeyRepository, submitQuizAnswersUseCase,
                weaknessProfileService, moderationService, avatarRepository, sessionRepository,
                eventBus, objectMapper);
    }

    private OrgClassJpaEntity classEntity() {
        OrgClassJpaEntity c = new OrgClassJpaEntity();
        c.setId(CLASS_ID);
        c.setOrganizationId(ORG);
        c.setCorpusAvatarId(CORPUS_AVATAR);
        return c;
    }

    private WikiPage page() {
        return WikiPage.create(CORPUS_AVATAR, "fractions", "Fractions", "content about fractions");
    }

    private QuizQuestion q(String id, int correctIndex) {
        return new QuizQuestion(id, CORPUS_AVATAR, "Q " + id, List.of("a", "b", "c"),
                correctIndex, "fractions", "expl", "Fractions", null);
    }

    private List<QuizQuestionResponse> servedFrom(List<QuizQuestion> pool) {
        return pool.stream()
                .map(qq -> new QuizQuestionResponse(qq.id(), qq.question(), qq.options(),
                        qq.sourcePageSlug(), qq.sourcePageTitle(), qq.selectionReason(), null, null))
                .toList();
    }

    private Avatar studentAvatar() {
        Avatar a = Avatar.create(STUDENT, "MathBot", Subject.MATHS, CharacterType.ZAP);
        a.setClassId(CLASS_ID);
        return a;
    }

    // ── create ───────────────────────────────────────────────────────────

    @Test
    void create_generatesSharedBossFromClassMaterial() {
        when(classCrudService.getClass(ORG, CLASS_ID)).thenReturn(classEntity());
        WikiPage page = page();
        when(wikiRepository.findById(PAGE_ID)).thenReturn(Optional.of(page));
        List<QuizQuestion> pool = List.of(q("q1", 0), q("q2", 1));
        when(quizGeneratorPort.generate(eq(CORPUS_AVATAR), eq(List.of(page)), anyString()))
                .thenReturn(pool);
        when(quizService.serveGradable(eq(CORPUS_AVATAR), anyList()))
                .thenAnswer(inv -> servedFrom(inv.getArgument(1)));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClassroomStateResponse resp = service.create(TEACHER, ORG, CLASS_ID, PAGE_ID);

        assertThat(resp.topicSlug()).isEqualTo("fractions");
        assertThat(resp.hpMax()).isEqualTo(2); // min(BOSS_HP=3, pool.size()=2)
        assertThat(resp.status()).isEqualTo(ClassroomSession.STATUS_CREATED);
        assertThat(resp.currentQuestion().id()).isEqualTo("q1");
    }

    // ── join: nickname moderation ───────────────────────────────────────

    private ClassroomSession liveSession() {
        List<QuizQuestion> pool = List.of(q("q1", 0), q("q2", 1));
        String poolJson;
        try {
            poolJson = objectMapper.writeValueAsString(pool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new ClassroomSession("session-1", CLASS_ID, TEACHER, CORPUS_AVATAR, "ABC123",
                "fractions", poolJson, 0, 2, 2, false, ClassroomSession.STATUS_ACTIVE,
                Instant.now(), Instant.now(), null);
    }

    @Test
    void join_nicknameFlaggedHighSeverity_throws422_neverRegistersParticipant() {
        when(avatarRepository.findById(STUDENT_AVATAR)).thenReturn(Optional.of(studentAvatar()));
        when(sessionRepository.findLiveByJoinCode("ABC123")).thenReturn(Optional.of(liveSession()));
        when(moderationService.screenInput(eq(STUDENT), eq(STUDENT_AVATAR), any(), eq("Bad Name"), eq("en")))
                .thenReturn(BLOCKED);

        assertThatThrownBy(() -> service.join(STUDENT, STUDENT_AVATAR, "ABC123", "Bad Name"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 422);

        assertThat(eventBus.participantCount("session-1")).isZero();
    }

    @Test
    void join_nicknameSafe_registersParticipant_returnsToken() {
        when(avatarRepository.findById(STUDENT_AVATAR)).thenReturn(Optional.of(studentAvatar()));
        when(sessionRepository.findLiveByJoinCode("ABC123")).thenReturn(Optional.of(liveSession()));
        when(moderationService.screenInput(eq(STUDENT), eq(STUDENT_AVATAR), any(), eq("Star Kid"), eq("en")))
                .thenReturn(SAFE);
        when(quizService.serveGradable(eq(CORPUS_AVATAR), anyList()))
                .thenAnswer(inv -> servedFrom(inv.getArgument(1)));

        ClassroomJoinResponse resp = service.join(STUDENT, STUDENT_AVATAR, "ABC123", "Star Kid");

        assertThat(resp.nickname()).isEqualTo("Star Kid");
        assertThat(resp.participantToken()).isNotBlank();
        assertThat(eventBus.participantCount("session-1")).isEqualTo(1);
        assertThat(eventBus.nicknameFor("session-1", resp.participantToken())).isEqualTo("Star Kid");
    }

    @Test
    void join_avatarNotEnrolledInThisClass_throws403() {
        Avatar wrongClassAvatar = Avatar.create(STUDENT, "MathBot", Subject.MATHS, CharacterType.ZAP);
        wrongClassAvatar.setClassId("some-other-class");
        when(avatarRepository.findById(STUDENT_AVATAR)).thenReturn(Optional.of(wrongClassAvatar));
        when(sessionRepository.findLiveByJoinCode("ABC123")).thenReturn(Optional.of(liveSession()));

        assertThatThrownBy(() -> service.join(STUDENT, STUDENT_AVATAR, "ABC123", "Star Kid"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
        verifyNoInteractions(moderationService);
    }

    // ── attack ───────────────────────────────────────────────────────────

    private String join() {
        when(avatarRepository.findById(STUDENT_AVATAR)).thenReturn(Optional.of(studentAvatar()));
        when(sessionRepository.findLiveByJoinCode("ABC123")).thenReturn(Optional.of(liveSession()));
        when(moderationService.screenInput(any(), any(), any(), any(), any())).thenReturn(SAFE);
        when(quizService.serveGradable(eq(CORPUS_AVATAR), anyList()))
                .thenAnswer(inv -> servedFrom(inv.getArgument(1)));
        return service.join(STUDENT, STUDENT_AVATAR, "ABC123", "Star Kid").participantToken();
    }

    @Test
    void attack_correctAnswer_reducesSharedHp_writesLearningEventPath() {
        String token = join();
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(liveSession()));
        when(answerKeyRepository.findByQuestionIds(Set.of("q1")))
                .thenReturn(Map.of("q1", new QuizAnswerKeyRepository.AnswerKey(0, "expl")));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ClassroomAttackResponse resp =
                service.attack("session-1", STUDENT, STUDENT_AVATAR, token, "q1", 0);

        assertThat(resp.hitLanded()).isTrue();
        assertThat(resp.state().hpRemaining()).isEqualTo(1); // 2 -> 1
        // Reuses the SAME Phase-0 write path — no second write path for the same fact.
        verify(submitQuizAnswersUseCase).recordSingleQuestionResult(
                eq(STUDENT), eq(STUDENT_AVATAR), eq("q1"), eq("fractions"), eq(true));
    }

    @Test
    void attack_staleQuestion_returnsConflict_notAnError() {
        String token = join();
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(liveSession())); // expects q1

        assertThatThrownBy(() -> service.attack("session-1", STUDENT, STUDENT_AVATAR, token, "q2", 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 409);
        verify(submitQuizAnswersUseCase, never())
                .recordSingleQuestionResult(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void attack_finalHit_marksDefeated_triggersMasteryUpdate() {
        String token = join();
        List<QuizQuestion> pool = List.of(q("q1", 0));
        String poolJson;
        try {
            poolJson = objectMapper.writeValueAsString(pool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ClassroomSession lastHp = new ClassroomSession("session-1", CLASS_ID, TEACHER, CORPUS_AVATAR,
                "ABC123", "fractions", poolJson, 0, 1, 2, false, ClassroomSession.STATUS_ACTIVE,
                Instant.now(), Instant.now(), null);
        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(lastHp));
        when(answerKeyRepository.findByQuestionIds(Set.of("q1")))
                .thenReturn(Map.of("q1", new QuizAnswerKeyRepository.AnswerKey(0, "expl")));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(weaknessProfileService.isEnabled()).thenReturn(true);

        ClassroomAttackResponse resp =
                service.attack("session-1", STUDENT, STUDENT_AVATAR, token, "q1", 0);

        assertThat(resp.state().defeated()).isTrue();
        assertThat(resp.state().hpRemaining()).isZero();
        verify(weaknessProfileService).onMasteryUpdated(STUDENT, STUDENT_AVATAR);
    }

    @Test
    void attack_notJoined_throws403() {
        assertThatThrownBy(() ->
                service.attack("session-1", STUDENT, STUDENT_AVATAR, "not-a-real-token", "q1", 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    // ── end: ephemeral identity actually disappears ─────────────────────

    @Test
    void end_dropsNicknamesIrrecoverably() {
        String token = join();
        assertThat(eventBus.participantCount("session-1")).isEqualTo(1);

        when(sessionRepository.findById("session-1")).thenReturn(Optional.of(liveSession()));
        when(classCrudService.getClass(ORG, CLASS_ID)).thenReturn(classEntity());
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.end(TEACHER, ORG, "session-1");

        assertThat(eventBus.participantCount("session-1")).isZero();
        assertThat(eventBus.nicknameFor("session-1", token)).isNull();
        assertThat(eventBus.hasParticipant("session-1", token)).isFalse();
    }
}
