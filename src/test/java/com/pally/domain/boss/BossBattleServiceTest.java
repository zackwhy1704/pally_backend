package com.pally.domain.boss;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.boss.dto.BossAttackResponse;
import com.pally.domain.boss.dto.BossStateResponse;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.QuizService;
import com.pally.domain.quiz.dto.QuizQuestionResponse;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.domain.quiz.usecase.SubmitQuizAnswersUseCase;
import com.pally.domain.weakness.WeaknessProfileService;
import com.pally.domain.weakness.WeaknessSignalRepository;
import com.pally.domain.weakness.WeaknessSignalRepository.TopicMastery;
import com.pally.domain.weakness.WeaknessSignalService;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BossBattleServiceTest {

    @Mock private AvatarRepository avatarRepository;
    @Mock private WikiRepository wikiRepository;
    @Mock private QuizGeneratorPort quizGeneratorPort;
    @Mock private QuizService quizService;
    @Mock private QuizAnswerKeyRepository answerKeyRepository;
    @Mock private SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    @Mock private WeaknessSignalRepository weaknessSignalRepository;
    @Mock private WeaknessProfileService weaknessProfileService;
    @Mock private BossInstanceRepository bossInstanceRepository;
    @Mock private AvatarSlotGuard avatarSlotGuard;

    private final WeaknessSignalService weaknessSignalService = new WeaknessSignalService(); // pure, real
    private final ObjectMapper objectMapper = new ObjectMapper();
    private BossBattleService service;

    private static final String USER = "user-1";
    private static final String AVATAR = "avatar-1";

    @BeforeEach
    void setUp() {
        service = new BossBattleService(
                avatarRepository, wikiRepository, quizGeneratorPort, quizService,
                answerKeyRepository, submitQuizAnswersUseCase, weaknessSignalRepository,
                weaknessSignalService, weaknessProfileService, bossInstanceRepository,
                avatarSlotGuard, objectMapper);
        lenient().when(bossInstanceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Not every test's boss reaches a "serve the next question" branch (e.g. defeat,
        // or a guard that throws first) — lenient so those don't fail on an unused stub.
        lenient().when(quizService.serveGradable(eq(AVATAR), anyList()))
                .thenAnswer(inv -> servedFrom(inv.getArgument(1)));
    }

    private Avatar personalAvatar() {
        return Avatar.reconstitute(AVATAR, USER, "MathBot", Subject.MATHS,
                CharacterType.ZAP, 0, Instant.now());
    }

    private QuizQuestion q(String id, int correctIndex, String slug) {
        return new QuizQuestion(id, AVATAR, "Q " + id,
                List.of("a", "b", "c"), correctIndex, slug, "expl", "Title", null);
    }

    private List<QuizQuestionResponse> servedFrom(List<QuizQuestion> pool) {
        return pool.stream()
                .map(qq -> new QuizQuestionResponse(qq.id(), qq.question(), qq.options(),
                        qq.sourcePageSlug(), qq.sourcePageTitle(), qq.selectionReason(), null, null))
                .toList();
    }

    // ── getActiveOrDetect ────────────────────────────────────────────────

    @Test
    void getActiveOrDetect_noWeakTopic_returnsNone() {
        when(bossInstanceRepository.findActiveByAvatarId(AVATAR)).thenReturn(Optional.empty());
        when(weaknessSignalRepository.findTopicMastery(USER, AVATAR)).thenReturn(List.of());

        BossStateResponse result = service.getActiveOrDetect(USER, AVATAR);

        assertThat(result).isEqualTo(BossStateResponse.none());
        verify(quizGeneratorPort, never()).generate(any(), any(), any());
    }

    @Test
    void getActiveOrDetect_weakTopicButNoMatchingWikiPage_returnsNone() {
        when(bossInstanceRepository.findActiveByAvatarId(AVATAR)).thenReturn(Optional.empty());
        when(weaknessSignalRepository.findTopicMastery(USER, AVATAR))
                .thenReturn(List.of(new TopicMastery("fractions", 0.2, 5)));
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(personalAvatar()));
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR, "fractions")).thenReturn(Optional.empty());

        BossStateResponse result = service.getActiveOrDetect(USER, AVATAR);

        assertThat(result.active()).isFalse();
        verify(quizGeneratorPort, never()).generate(any(), any(), any());
    }

    @Test
    void getActiveOrDetect_spawnsBossFromTheWeakestTopic_hpCappedAtPoolSize() {
        when(bossInstanceRepository.findActiveByAvatarId(AVATAR)).thenReturn(Optional.empty());
        // Two weak topics — "fractions" is WEAKER (lower ratio) so it must be picked, not "decimals".
        when(weaknessSignalRepository.findTopicMastery(USER, AVATAR)).thenReturn(List.of(
                new TopicMastery("decimals", 0.5, 4),
                new TopicMastery("fractions", 0.1, 4)));
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(personalAvatar()));
        WikiPage page = WikiPage.create(AVATAR, "fractions", "Fractions", "content");
        when(wikiRepository.findByAvatarIdAndSlug(AVATAR, "fractions")).thenReturn(Optional.of(page));

        List<QuizQuestion> pool = List.of(q("q1", 0, "fractions"), q("q2", 1, "fractions"));
        when(quizGeneratorPort.generate(eq(AVATAR), eq(List.of(page)), anyString())).thenReturn(pool);
        when(quizService.serveGradable(eq(AVATAR), eq(pool))).thenReturn(servedFrom(pool));

        BossStateResponse result = service.getActiveOrDetect(USER, AVATAR);

        ArgumentCaptor<BossInstance> cap = ArgumentCaptor.forClass(BossInstance.class);
        verify(bossInstanceRepository).save(cap.capture());
        BossInstance saved = cap.getValue();
        assertThat(saved.topicSlug()).isEqualTo("fractions"); // weakest, not "decimals"
        assertThat(saved.hpMax()).isEqualTo(2);   // min(BOSS_HP=3, pool.size()=2)
        assertThat(saved.hpRemaining()).isEqualTo(2);
        assertThat(saved.currentIndex()).isEqualTo(0);
        assertThat(saved.defeated()).isFalse();

        assertThat(result.active()).isTrue();
        assertThat(result.topicSlug()).isEqualTo("fractions");
        assertThat(result.hpMax()).isEqualTo(2);
        assertThat(result.currentQuestion().id()).isEqualTo("q1");
    }

    @Test
    void getActiveOrDetect_existingActiveBoss_returnsIt_neverRespawns() {
        List<QuizQuestion> pool = List.of(q("q1", 0, "fractions"));
        BossInstance active = new BossInstance("boss-existing", USER, AVATAR, "fractions",
                toJson(pool), 0, 2, 3, false, false, Instant.now(), null);
        when(bossInstanceRepository.findActiveByAvatarId(AVATAR)).thenReturn(Optional.of(active));
        when(quizService.serveGradable(eq(AVATAR), eq(List.of(pool.get(0)))))
                .thenReturn(servedFrom(pool));

        BossStateResponse result = service.getActiveOrDetect(USER, AVATAR);

        assertThat(result.id()).isEqualTo(active.id());
        assertThat(result.hpRemaining()).isEqualTo(2);
        verify(quizGeneratorPort, never()).generate(any(), any(), any());
        verify(bossInstanceRepository, never()).save(any());
    }

    // ── attack ───────────────────────────────────────────────────────────

    private BossInstance twoQuestionBoss(int hpRemaining, int hpMax, int currentIndex, boolean defeated) {
        List<QuizQuestion> pool = List.of(q("q0", 1, "fractions"), q("q1", 2, "fractions"));
        return new BossInstance("boss-1", USER, AVATAR, "fractions", toJson(pool),
                currentIndex, hpRemaining, hpMax, defeated, defeated, Instant.now(), null);
    }

    private String toJson(List<QuizQuestion> pool) {
        try {
            return objectMapper.writeValueAsString(pool);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void attack_correctAnswer_reducesHp_recordsThroughExistingQuizWritePath() {
        BossInstance boss = twoQuestionBoss(3, 3, 0, false);
        when(bossInstanceRepository.findById("boss-1")).thenReturn(Optional.of(boss));
        when(answerKeyRepository.findByQuestionIds(Set.of("q0")))
                .thenReturn(Map.of("q0", new QuizAnswerKeyRepository.AnswerKey(1, "expl")));

        BossAttackResponse response = service.attack("boss-1", USER, AVATAR, "q0", 1);

        assertThat(response.hitLanded()).isTrue();
        assertThat(response.state().hpRemaining()).isEqualTo(2);
        assertThat(response.state().defeated()).isFalse();

        // Reuses the EXISTING quiz_question_results + learning_event write path —
        // no bespoke second write path for the same fact.
        verify(submitQuizAnswersUseCase).recordSingleQuestionResult(
                eq(USER), eq(AVATAR), eq("q0"), eq("fractions"), eq(true));

        ArgumentCaptor<BossInstance> cap = ArgumentCaptor.forClass(BossInstance.class);
        verify(bossInstanceRepository).save(cap.capture());
        assertThat(cap.getValue().hpRemaining()).isEqualTo(2);
        assertThat(cap.getValue().currentIndex()).isEqualTo(1);
    }

    @Test
    void attack_wrongAnswer_hpUnchanged_nonPunitive_stillRecordsAttempt() {
        BossInstance boss = twoQuestionBoss(3, 3, 0, false);
        when(bossInstanceRepository.findById("boss-1")).thenReturn(Optional.of(boss));
        when(answerKeyRepository.findByQuestionIds(Set.of("q0")))
                .thenReturn(Map.of("q0", new QuizAnswerKeyRepository.AnswerKey(1, "expl")));

        BossAttackResponse response = service.attack("boss-1", USER, AVATAR, "q0", 2); // wrong

        assertThat(response.hitLanded()).isFalse();
        assertThat(response.state().hpRemaining()).isEqualTo(3); // unchanged — non-punitive
        assertThat(response.state().defeated()).isFalse();
        verify(submitQuizAnswersUseCase).recordSingleQuestionResult(
                eq(USER), eq(AVATAR), eq("q0"), eq("fractions"), eq(false));
    }

    @Test
    void attack_finalHit_marksDefeated_unlocksReward_triggersMasteryUpdate() {
        BossInstance boss = twoQuestionBoss(1, 3, 0, false); // last hit
        when(bossInstanceRepository.findById("boss-1")).thenReturn(Optional.of(boss));
        when(answerKeyRepository.findByQuestionIds(Set.of("q0")))
                .thenReturn(Map.of("q0", new QuizAnswerKeyRepository.AnswerKey(1, "expl")));
        when(weaknessProfileService.isEnabled()).thenReturn(true);

        BossAttackResponse response = service.attack("boss-1", USER, AVATAR, "q0", 1);

        assertThat(response.state().defeated()).isTrue();
        assertThat(response.state().rewardUnlocked()).isTrue();
        assertThat(response.state().hpRemaining()).isEqualTo(0);
        assertThat(response.state().currentQuestion()).isNull();
        verify(weaknessProfileService).onMasteryUpdated(USER, AVATAR);
        verify(quizService, never()).serveGradable(eq(AVATAR), anyList()); // no next question served
    }

    @Test
    void attack_wrongQuestionId_batlleStateOutOfSync_throws400_neverMutatesState() {
        BossInstance boss = twoQuestionBoss(3, 3, 0, false); // expects "q0"
        when(bossInstanceRepository.findById("boss-1")).thenReturn(Optional.of(boss));

        assertThatThrownBy(() -> service.attack("boss-1", USER, AVATAR, "q1", 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);

        verify(submitQuizAnswersUseCase, never())
                .recordSingleQuestionResult(any(), any(), any(), any(), anyBoolean());
        verify(bossInstanceRepository, never()).save(any());
    }

    @Test
    void attack_callerDoesNotOwnBoss_throws404_idor() {
        BossInstance boss = twoQuestionBoss(3, 3, 0, false); // owned by USER
        when(bossInstanceRepository.findById("boss-1")).thenReturn(Optional.of(boss));

        assertThatThrownBy(() -> service.attack("boss-1", "attacker", AVATAR, "q0", 1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 404);
    }

    @Test
    void attack_alreadyDefeatedBoss_throws400() {
        BossInstance boss = twoQuestionBoss(0, 3, 2, true);
        when(bossInstanceRepository.findById("boss-1")).thenReturn(Optional.of(boss));

        assertThatThrownBy(() -> service.attack("boss-1", USER, AVATAR, "q0", 1))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }
}
