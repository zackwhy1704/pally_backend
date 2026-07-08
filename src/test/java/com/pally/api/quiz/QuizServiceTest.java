package com.pally.api.quiz;

import com.pally.domain.quiz.QuizService;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizAnswerRecordJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.usecase.GetDailyQuizUseCase;
import com.pally.domain.quiz.usecase.GetFlashcardsUseCase;
import com.pally.domain.quiz.usecase.RateFlashcardUseCase;
import com.pally.domain.quiz.usecase.SubmitQuizAnswersUseCase;
import com.pally.shared.exception.AvatarNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;

/**
 * Unit tests for {@link QuizService} — the ownership guard on error-patterns,
 * the quiz-status mastery counting (0.7 threshold), and the generate-flashcards
 * no-wiki branch. (Logic moved here from the controller in the refactor.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QuizServiceTest {

    @Mock GetDailyQuizUseCase getDailyQuizUseCase;
    @Mock SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    @Mock com.pally.domain.quiz.QuizIdempotencyRepository quizIdempotencyRepository;
    @Mock GetFlashcardsUseCase getFlashcardsUseCase;
    @Mock RateFlashcardUseCase rateFlashcardUseCase;
    @Mock QuizAnswerRecordJpaRepository quizAnswerRecordRepository;
    @Mock QuizQuestionResultJpaRepository quizQuestionResultRepository;
    @Mock AvatarJpaRepository avatarRepository;
    @Mock WikiRepository wikiRepository;
    @Mock FlashcardRepository flashcardRepository;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;
    @Mock com.pally.domain.quiz.QuizAnswerKeyRepository answerKeyRepository;

    QuizService service;

    private static final String USER = "user-1";
    private static final String AVATAR = "avatar-1";

    @BeforeEach
    void setUp() {
        service = new QuizService(
                getDailyQuizUseCase, submitQuizAnswersUseCase, quizIdempotencyRepository,
                getFlashcardsUseCase, rateFlashcardUseCase, quizAnswerRecordRepository,
                quizQuestionResultRepository, avatarRepository, wikiRepository,
                flashcardRepository, flashcardGenerator, answerKeyRepository);
    }

    @Test
    void getErrorPatterns_avatarNotOwned_throws404() {
        // findById empty → ownership filter throws (no existence leak).
        when(avatarRepository.findById(AVATAR)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getErrorPatterns(USER, AVATAR))
                .isInstanceOf(AvatarNotFoundException.class);
    }

    @Test
    void getQuizStatus_countsOnlyTopicsAtOrAboveMasteryThreshold() {
        when(quizQuestionResultRepository.takenToday(USER, AVATAR)).thenReturn(true);
        when(quizQuestionResultRepository.findAllTopicMasteryByAvatar(USER, AVATAR))
                .thenReturn(List.of(
                        new Object[]{"a", 0.9},   // mastered
                        new Object[]{"b", 0.7},   // mastered (boundary)
                        new Object[]{"c", 0.69})); // not mastered
        when(wikiRepository.countActiveByAvatarId(AVATAR)).thenReturn(5);

        Map<String, Object> status = service.getQuizStatus(USER, AVATAR);

        assertThat(status.get("takenToday")).isEqualTo(true);
        assertThat(status.get("totalTopics")).isEqualTo(5);
        assertThat(status.get("masteredTopics")).isEqualTo(2);
    }

    @Test
    void generateFlashcards_noWikiPages_returnsZeroAndHasWikiFalse() {
        when(wikiRepository.findByAvatarId(AVATAR)).thenReturn(List.of());

        Map<String, Object> result = service.generateFlashcards(USER, AVATAR, true);

        assertThat(result.get("hasWikiPages")).isEqualTo(false);
        assertThat(result.get("generated")).isEqualTo(0);
    }

    @Test
    void generateFlashcards_largeCorpus_autoBackfill_defersToCta_generatesNothing() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "autoGenMaxPages", 15);
        when(wikiRepository.findByAvatarId(AVATAR)).thenReturn(manyPages(20));

        // confirmed=false = auto-backfill on screen open → must NOT run the loop.
        Map<String, Object> result = service.generateFlashcards(USER, AVATAR, false);

        assertThat(result.get("needsConfirmation")).isEqualTo(true);
        assertThat(result.get("pageCount")).isEqualTo(20);
        verifyNoInteractions(flashcardGenerator); // no synchronous all-pages hang
    }

    @Test
    void generateFlashcards_largeCorpus_confirmed_generates() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "autoGenMaxPages", 15);
        when(wikiRepository.findByAvatarId(AVATAR)).thenReturn(manyPages(20));

        Map<String, Object> result = service.generateFlashcards(USER, AVATAR, true);

        assertThat(result.get("needsConfirmation")).isEqualTo(false);
        verify(flashcardGenerator, times(20)).generateAndSaveForPage(eq(AVATAR), any());
    }

    private List<com.pally.domain.knowledge.WikiPage> manyPages(int n) {
        List<com.pally.domain.knowledge.WikiPage> pages = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            pages.add(com.pally.domain.knowledge.WikiPage.create(
                    AVATAR, "slug-" + i, "Title " + i, "Content " + i));
        }
        return pages;
    }

    // ── Serving chokepoint: key persistence + exposure split ──────────────────

    private com.pally.domain.quiz.QuizQuestion q() {
        return new com.pally.domain.quiz.QuizQuestion(
                "q1", AVATAR, "2+2?", List.of("3", "4", "5"), 1, "slug", "four");
    }

    @Test
    void getDailyQuiz_teacherGradedCentreAvatar_withholdsBothAnswerFields_andPersistsKey() {
        when(getDailyQuizUseCase.execute(AVATAR, USER)).thenReturn(List.of(q()));
        when(avatarRepository.existsByIdAndCentreAvatarTrue(AVATAR)).thenReturn(true);

        var served = service.getDailyQuiz(USER, AVATAR);

        // BOTH answer-revealing fields must be withheld for a teacher-graded quiz...
        assertThat(served).hasSize(1);
        assertThat(served.get(0).correctIndex())
                .as("teacher-graded quiz must not expose the answer index")
                .isNull();
        assertThat(served.get(0).explanation())
                .as("teacher-graded quiz must not expose the explanation (it "
                        + "reveals the answer just like the index)")
                .isNull();
        // ...the SAFE fields are still present...
        assertThat(served.get(0).question()).isEqualTo("2+2?");
        assertThat(served.get(0).options()).isNotEmpty();
        // ...and the server key must be persisted so submit can grade + explain.
        org.mockito.Mockito.verify(answerKeyRepository)
                .saveKeys(eq(AVATAR), anyList());
    }

    @Test
    void getDailyQuiz_b2cSoloAvatar_keepsBothAnswerFields_forInstantFeedback() {
        when(getDailyQuizUseCase.execute(AVATAR, USER)).thenReturn(List.of(q()));
        when(avatarRepository.existsByIdAndCentreAvatarTrue(AVATAR)).thenReturn(false);

        var served = service.getDailyQuiz(USER, AVATAR);

        // Solo B2C quiz keeps both (stated tradeoff: instant pre-submit feedback).
        assertThat(served.get(0).correctIndex()).isEqualTo(1);
        assertThat(served.get(0).explanation()).isEqualTo("four");
        org.mockito.Mockito.verify(answerKeyRepository).saveKeys(eq(AVATAR), anyList());
    }

    @Test
    void getDailyQuiz_centreAvatar_keyPersistenceFails_degradesToExposingKey() {
        when(getDailyQuizUseCase.execute(AVATAR, USER)).thenReturn(List.of(q()));
        when(avatarRepository.existsByIdAndCentreAvatarTrue(AVATAR)).thenReturn(true);
        org.mockito.Mockito.doThrow(new RuntimeException("db down"))
                .when(answerKeyRepository).saveKeys(eq(AVATAR), anyList());

        var served = service.getDailyQuiz(USER, AVATAR);

        // Availability over secrecy on a rare write failure: still gradable.
        assertThat(served.get(0).correctIndex()).isEqualTo(1);
    }

    // ── #5 quiz-submit idempotency ───────────────────────────────────────────

    private com.pally.domain.quiz.dto.SubmitAnswersRequest reqWithKey(String key) {
        return new com.pally.domain.quiz.dto.SubmitAnswersRequest(
                Map.of("q1", 0), Map.of("q1", 0), Map.of(), Map.of(), 30, key);
    }

    private com.pally.domain.quiz.QuizResult sampleResult(int xp) {
        return new com.pally.domain.quiz.QuizResult("s1", 1, 1, xp, 5, false, 2);
    }

    @Test
    void submitAnswers_replayAfterCompletion_returnsStoredResult_withoutRegrading() {
        var stored = sampleResult(20);
        when(quizIdempotencyRepository.findResult(USER, "k1")).thenReturn(Optional.of(stored));

        var result = service.submitAnswers(USER, AVATAR, reqWithKey("k1"));

        assertThat(result).isSameAs(stored);
        // The grader is never invoked on a replay → no double XP / duplicate rows.
        verify(submitQuizAnswersUseCase, never())
                .executeWithIdempotency(any(), any(), any(), any(), anyInt(), anyString());
        verify(submitQuizAnswersUseCase, never()).execute(any(), any(), any(), any(), anyInt());
    }

    @Test
    void submitAnswers_concurrentLoser_returnsWinnersStoredResult() {
        var winner = sampleResult(20);
        // First check: not yet present. After the claim race is lost, the winner's
        // committed result is present.
        when(quizIdempotencyRepository.findResult(USER, "k2"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(submitQuizAnswersUseCase.executeWithIdempotency(
                any(), any(), any(), any(), anyInt(), eq("k2")))
                .thenThrow(new com.pally.domain.quiz.DuplicateSubmissionException(USER, "k2"));

        var result = service.submitAnswers(USER, AVATAR, reqWithKey("k2"));

        assertThat(result).isSameAs(winner);
    }

    @Test
    void submitAnswers_firstSubmitWithKey_gradesOnceViaIdempotentPath() {
        var graded = sampleResult(20);
        when(quizIdempotencyRepository.findResult(USER, "k3")).thenReturn(Optional.empty());
        when(submitQuizAnswersUseCase.executeWithIdempotency(
                any(), any(), any(), any(), anyInt(), eq("k3"))).thenReturn(graded);

        var result = service.submitAnswers(USER, AVATAR, reqWithKey("k3"));

        assertThat(result).isSameAs(graded);
        verify(submitQuizAnswersUseCase, never()).execute(any(), any(), any(), any(), anyInt());
    }

    @Test
    void submitAnswers_noKey_gradesViaLegacyPath() {
        var graded = sampleResult(20);
        when(submitQuizAnswersUseCase.execute(any(), any(), any(), any(), anyInt()))
                .thenReturn(graded);

        var result = service.submitAnswers(USER, AVATAR, reqWithKey(null));

        assertThat(result).isSameAs(graded);
        // No key → never touches the idempotency store or the idempotent path.
        verify(submitQuizAnswersUseCase, never())
                .executeWithIdempotency(any(), any(), any(), any(), anyInt(), anyString());
    }
}
