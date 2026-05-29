package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.progress.ActivityLogService;
import com.pally.domain.progress.BadgeService;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.XpService;
import com.pally.domain.quiz.AnswerSubmission;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.QuizResult;
import com.pally.domain.referral.ReferralService;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

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
    @Mock FlashcardRepository flashcardRepository;
    @Mock UserRepository userRepository;
    @Mock ActivityLogService activityLogService;
    @Mock BadgeService badgeService;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;
    @Mock WikiRepository wikiRepository;
    @Mock ReferralService referralService;
    @Mock XpService xpService;

    @InjectMocks SubmitQuizAnswersUseCase useCase;

    private static final String USER = "u1";
    private static final String AVATAR = "a1";

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
        when(avatarRepository.existsByIdAndUserId(AVATAR, USER))
                .thenReturn(false);

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
}
