package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.XpService;
import com.pally.domain.user.UserRepository;
import com.pally.domain.quiz.AnswerSubmission;
import com.pally.domain.quiz.CardRating;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.QuizResult;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.referral.ReferralService;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaEntity;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// The audit's T1 atomicity case for the quiz path. We can't easily prove
/// @Transactional rollback in a unit test without Spring TX context, but
/// we CAN prove the use case routes through XpService (the new decay
/// pipeline) and that an unknown avatar fails fast — both are concrete
/// regressions that would silently bypass the farm-stop rules.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SubmitQuizAnswersUseCaseTest {

    @Mock AvatarRepository avatarRepository;
    @Mock AvatarSlotGuard avatarSlotGuard;
    @Mock FlashcardRepository flashcardRepository;
    @Mock UserRepository userRepository;
    @Mock ActivityLogService activityLogService;
    @Mock BadgeService badgeService;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;
    @Mock WikiRepository wikiRepository;
    @Mock ReferralService referralService;
    @Mock XpService xpService;
    @Mock QuizAnswerKeyRepository answerKeyRepository;
    @Mock ObjectProvider<SubmitQuizAnswersUseCase> selfProvider;

    @InjectMocks SubmitQuizAnswersUseCase useCase;

    private static final String USER = "u1";
    private static final String AVATAR = "a1";

    @BeforeEach
    void wireSelfProxyAndDefaultKeys() {
        // The REQUIRES_NEW per-question persistence is invoked via the self-
        // proxy; in a unit test there is no Spring proxy, so route it back to
        // the real instance so the secondary write actually runs.
        when(selfProvider.getObject()).thenReturn(useCase);
        // Default: no persisted server key → grading falls back to the client
        // map, preserving the existing tests' expectations. Integrity test
        // below overrides this with real server keys.
        when(answerKeyRepository.findCorrectIndexes(any())).thenReturn(Map.of());
    }

    private Avatar mathsAvatar() {
        return Avatar.reconstitute(
                AVATAR, USER, "MathBot", Subject.MATHS,
                CharacterType.ZAP, 0, Instant.now());
    }

    private XpService.QuizAward award(int xp, int stars, boolean variety, double mult) {
        return new XpService.QuizAward(xp, stars, 20, mult, variety, 0,
                0, 0, new UserRepository.XpResult(100, 1, 1, false, null));
    }

    @Test
    void unknownAvatar_throws_beforeAnyMutation() {
        AnswerSubmission sub = new AnswerSubmission(
                AVATAR, USER, Map.of("q1", 0));
        // Fix 2: AvatarSlotGuard now guards this use-case.
        // When the avatar doesn't exist, the guard (or the old check) throws.
        doThrow(new AvatarNotFoundException(AVATAR))
                .when(avatarSlotGuard).requireActive(AVATAR, USER);

        assertThatThrownBy(() -> useCase.execute(sub, Map.of("q1", 0)))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void quizSubmit_routesXpThroughXpService_withAvatarSubject() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER))
                .thenReturn(true);
        when(avatarRepository.findById(AVATAR))
                .thenReturn(Optional.of(mathsAvatar()));
        when(flashcardRepository.findDueByAvatarId(AVATAR))
                .thenReturn(List.of());
        when(xpService.awardForQuiz(eq(USER), eq(AVATAR), eq(Subject.MATHS),
                anyInt(), anyInt(), anyInt()))
                .thenReturn(award(30, 15, true, 1.5));

        AnswerSubmission sub = new AnswerSubmission(
                AVATAR, USER, Map.of("q1", 0, "q2", 1));
        // q1 correct, q2 wrong → 1 correct out of 2.
        QuizResult result = useCase.execute(sub, Map.of("q1", 0, "q2", 0));

        assertThat(result.score()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(2);
        // baseXp = BASE_XP(20) + correct(1)*XP_PER_CORRECT(4) = 24
        // XpService returns the variety-bonus amount (30) - that's the
        // value the use case must surface, NOT the raw 24.
        assertThat(result.xpEarned()).isEqualTo(30);
        assertThat(result.starsEarned()).isEqualTo(15);

        // Verify the use case asked XpService for the right base.
        ArgumentCaptor<Integer> baseCap = ArgumentCaptor.forClass(Integer.class);
        verify(xpService).awardForQuiz(eq(USER), eq(AVATAR), eq(Subject.MATHS),
                baseCap.capture(), anyInt(), anyInt());
        assertThat(baseCap.getValue()).isEqualTo(24);
    }

    @Test
    void quizSubmit_missingAvatar_passesNullSubject_doesNotCrash() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER))
                .thenReturn(true);
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.empty());
        when(flashcardRepository.findDueByAvatarId(AVATAR))
                .thenReturn(List.of());
        when(xpService.awardForQuiz(eq(USER), eq(AVATAR), eq(null),
                anyInt(), anyInt(), anyInt()))
                .thenReturn(award(20, 10, false, 1.0));

        AnswerSubmission sub = new AnswerSubmission(
                AVATAR, USER, Map.of("q1", 0));
        QuizResult result = useCase.execute(sub, Map.of("q1", 0));
        assertThat(result.xpEarned()).isEqualTo(20);
        verify(xpService).awardForQuiz(eq(USER), eq(AVATAR), eq(null),
                eq(24), anyInt(), anyInt());
    }

    @Test
    void quizSubmit_alwaysCallsReferralActivation() {
        // Referral activation must run even if the user has no pending
        // referral — the service is idempotent and no-ops on missing rows.
        // Catching a regression where the call is skipped silently.
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER))
                .thenReturn(true);
        when(avatarRepository.findById(AVATAR))
                .thenReturn(Optional.of(mathsAvatar()));
        when(flashcardRepository.findDueByAvatarId(AVATAR))
                .thenReturn(List.of());
        when(xpService.awardForQuiz(anyString(), anyString(), any(),
                anyInt(), anyInt(), anyInt()))
                .thenReturn(award(20, 10, false, 1.0));

        useCase.execute(new AnswerSubmission(AVATAR, USER, Map.of("q1", 0)),
                Map.of("q1", 0));

        verify(referralService).onFirstQuizAnswer(USER);
        verify(badgeService).grantFirstAction(USER, BadgeService.BadgeType.FIRST_QUIZ);
    }

    @Test
    void quizSubmit_logsRealDuration_notZero() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER)).thenReturn(true);
        when(avatarRepository.findById(AVATAR))
                .thenReturn(Optional.of(mathsAvatar()));
        when(flashcardRepository.findDueByAvatarId(AVATAR)).thenReturn(List.of());
        when(xpService.awardForQuiz(anyString(), anyString(), any(),
                anyInt(), anyInt(), anyInt()))
                .thenReturn(award(30, 15, false, 1.0));

        useCase.execute(new AnswerSubmission(AVATAR, USER, Map.of("q1", 0)),
                Map.of("q1", 0), Map.of(), Map.of(), 120);

        // The activity row must carry the real 120s, not the old hardcoded 0.
        verify(activityLogService).log(eq(USER), eq(AVATAR),
                eq(ActivityLogService.TYPE_QUIZ), eq(120), anyInt());
    }

    @Test
    void quizSubmit_legacyOverload_logsZeroDuration() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER)).thenReturn(true);
        when(avatarRepository.findById(AVATAR))
                .thenReturn(Optional.of(mathsAvatar()));
        when(flashcardRepository.findDueByAvatarId(AVATAR)).thenReturn(List.of());
        when(xpService.awardForQuiz(anyString(), anyString(), any(),
                anyInt(), anyInt(), anyInt()))
                .thenReturn(award(30, 15, false, 1.0));

        // The 3-arg overload (legacy callers) defaults duration to 0.
        useCase.execute(new AnswerSubmission(AVATAR, USER, Map.of("q1", 0)),
                Map.of("q1", 0), Map.of());

        verify(activityLogService).log(eq(USER), eq(AVATAR),
                eq(ActivityLogService.TYPE_QUIZ), eq(0), anyInt());
    }

    // ── QUIZ: per-slug SM-2 rating (not per-session ratio) ────────────────

    @Test
    @SuppressWarnings("unchecked")
    void quizSubmit_sm2RatesEachCardByItsTopicSlug_notWholeQuizRatio() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER)).thenReturn(true);
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(mathsAvatar()));
        when(xpService.awardForQuiz(eq(USER), eq(AVATAR), eq(Subject.MATHS),
                anyInt(), anyInt(), anyInt())).thenReturn(award(30, 15, false, 1.0));

        // slug-A card (questions all correct → EASY), slug-B card (all wrong →
        // HARD), slug-C card (no questions this quiz → untouched).
        FlashCard cardA = card("card-a", "slug-a");
        FlashCard cardB = card("card-b", "slug-b");
        FlashCard cardC = card("card-c", "slug-c");
        when(flashcardRepository.findDueByAvatarId(AVATAR))
                .thenReturn(List.of(cardA, cardB, cardC));

        // q1,q2 → slug-a (both correct); q3 → slug-b (wrong).
        AnswerSubmission sub = new AnswerSubmission(
                AVATAR, USER, Map.of("q1", 0, "q2", 0, "q3", 1));
        Map<String, Integer> correct = Map.of("q1", 0, "q2", 0, "q3", 0);
        Map<String, String> topics = Map.of("q1", "slug-a", "q2", "slug-a", "q3", "slug-b");

        useCase.execute(sub, correct, topics);

        ArgumentCaptor<List<FlashCard>> cap = ArgumentCaptor.forClass(List.class);
        verify(flashcardRepository).saveAll(cap.capture());
        List<FlashCard> saved = cap.getValue();

        // slug-c card has no fresh signal → not rescheduled.
        assertThat(saved).extracting(FlashCard::id)
                .containsExactlyInAnyOrder("card-a", "card-b");

        FlashCard savedA = saved.stream().filter(c -> c.id().equals("card-a")).findFirst().orElseThrow();
        FlashCard savedB = saved.stream().filter(c -> c.id().equals("card-b")).findFirst().orElseThrow();
        assertThat(savedA.lastRating()).isEqualTo(CardRating.EASY);
        assertThat(savedB.lastRating()).isEqualTo(CardRating.HARD);
    }

    private FlashCard card(String id, String sourceSlug) {
        return new FlashCard(id, AVATAR, "front", "back", sourceSlug,
                CardRating.OKAY, Instant.now(), 1, 2.5, 1);
    }

    // ── GRADE INTEGRITY: server key wins over a tampered client map ────────

    @Test
    void serverAnswerKeyIsAuthoritative_tamperedClientMapCannotInflateScore() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER)).thenReturn(true);
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(mathsAvatar()));
        when(flashcardRepository.findDueByAvatarId(AVATAR)).thenReturn(List.of());
        when(xpService.awardForQuiz(anyString(), anyString(), any(),
                anyInt(), anyInt(), anyInt())).thenReturn(award(24, 12, false, 1.0));

        // SERVER says: q1 correct index = 0, q2 correct index = 0.
        when(answerKeyRepository.findCorrectIndexes(any()))
                .thenReturn(Map.of("q1", 0, "q2", 0));

        // Student actually answered q1=0 (right) and q2=1 (WRONG) → real score 1/2.
        AnswerSubmission sub = new AnswerSubmission(AVATAR, USER, Map.of("q1", 0, "q2", 1));
        // TAMPERED client map claims BOTH are correct (q2's "correct" = 1).
        Map<String, Integer> tampered = Map.of("q1", 0, "q2", 1);

        QuizResult result = useCase.execute(sub, tampered);

        // Server key wins: the wrong answer stays wrong, score is NOT inflated.
        assertThat(result.score()).isEqualTo(1);
        assertThat(result.total()).isEqualTo(2);

        // The persisted analytics row (what the teacher dashboard reads) must
        // also reflect server-graded correctness, not the tampered claim.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<QuizQuestionResultJpaEntity>> cap =
                ArgumentCaptor.forClass(List.class);
        verify(quizResultRepo).saveAll(cap.capture());
        Map<String, Boolean> byQuestion = cap.getValue().stream()
                .collect(java.util.stream.Collectors.toMap(
                        QuizQuestionResultJpaEntity::getQuestionId,
                        QuizQuestionResultJpaEntity::isWasCorrect));
        assertThat(byQuestion.get("q1")).isTrue();
        assertThat(byQuestion.get("q2")).isFalse(); // tampered map could not flip this
    }

    // ── POST-SUBMIT FEEDBACK: the correct answer is revealed only after submit
    //    (the served teacher-graded question withheld it) ────────────────────

    @Test
    void submit_returnsPerQuestionFeedback_revealingCorrectAnswerAfterSubmit() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER)).thenReturn(true);
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(mathsAvatar()));
        when(flashcardRepository.findDueByAvatarId(AVATAR)).thenReturn(List.of());
        when(xpService.awardForQuiz(anyString(), anyString(), any(),
                anyInt(), anyInt(), anyInt())).thenReturn(award(24, 12, false, 1.0));
        // Server key: q1 correct=0, q2 correct=2.
        when(answerKeyRepository.findCorrectIndexes(any()))
                .thenReturn(Map.of("q1", 0, "q2", 2));

        // Student answered q1=0 (right), q2=1 (wrong). No client map at all —
        // the teacher-graded quiz never shipped the key.
        AnswerSubmission sub = new AnswerSubmission(AVATAR, USER, Map.of("q1", 0, "q2", 1));
        QuizResult result = useCase.execute(sub, Map.of());

        assertThat(result.feedback()).hasSize(2);
        Map<String, QuizResult.QuestionFeedback> byId = result.feedback().stream()
                .collect(java.util.stream.Collectors.toMap(
                        QuizResult.QuestionFeedback::questionId, f -> f));
        assertThat(byId.get("q1").wasCorrect()).isTrue();
        assertThat(byId.get("q1").correctIndex()).isEqualTo(0);
        assertThat(byId.get("q2").wasCorrect()).isFalse();
        // The correct answer is revealed here, post-submit — feedback preserved.
        assertThat(byId.get("q2").correctIndex()).isEqualTo(2);
    }

    // ── TRANSACTION-POISONING: a secondary write failure must not abort the
    //    primary score/XP path ───────────────────────────────────────────────

    @Test
    void perQuestionResultWriteFailure_doesNotRollBackTheScoreOrXp() {
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER)).thenReturn(true);
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.of(mathsAvatar()));
        when(flashcardRepository.findDueByAvatarId(AVATAR)).thenReturn(List.of());
        when(xpService.awardForQuiz(anyString(), anyString(), any(),
                anyInt(), anyInt(), anyInt())).thenReturn(award(28, 14, false, 1.0));

        // The best-effort per-question persistence blows up (the kind of JPA
        // failure that, inside the primary @Transactional, would mark it
        // rollback-only and throw UnexpectedRollbackException on commit).
        doThrow(new RuntimeException("db down"))
                .when(quizResultRepo).saveAll(any());

        AnswerSubmission sub = new AnswerSubmission(AVATAR, USER, Map.of("q1", 0, "q2", 0));
        QuizResult result = useCase.execute(sub, Map.of("q1", 0, "q2", 0));

        // Primary result still computed + returned; XP still awarded.
        assertThat(result.score()).isEqualTo(2);
        assertThat(result.xpEarned()).isEqualTo(28);
        verify(xpService).awardForQuiz(eq(USER), eq(AVATAR), eq(Subject.MATHS),
                anyInt(), anyInt(), anyInt());
    }
}
