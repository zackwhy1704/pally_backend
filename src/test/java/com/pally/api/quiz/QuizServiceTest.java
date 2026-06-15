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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
    @Mock GetFlashcardsUseCase getFlashcardsUseCase;
    @Mock RateFlashcardUseCase rateFlashcardUseCase;
    @Mock QuizAnswerRecordJpaRepository quizAnswerRecordRepository;
    @Mock QuizQuestionResultJpaRepository quizQuestionResultRepository;
    @Mock AvatarJpaRepository avatarRepository;
    @Mock WikiRepository wikiRepository;
    @Mock FlashcardRepository flashcardRepository;
    @Mock ClaudeFlashcardGenerator flashcardGenerator;

    QuizService service;

    private static final String USER = "user-1";
    private static final String AVATAR = "avatar-1";

    @BeforeEach
    void setUp() {
        service = new QuizService(
                getDailyQuizUseCase, submitQuizAnswersUseCase, getFlashcardsUseCase,
                rateFlashcardUseCase, quizAnswerRecordRepository, quizQuestionResultRepository,
                avatarRepository, wikiRepository, flashcardRepository, flashcardGenerator);
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

        Map<String, Object> result = service.generateFlashcards(USER, AVATAR);

        assertThat(result.get("hasWikiPages")).isEqualTo(false);
        assertThat(result.get("generated")).isEqualTo(0);
    }
}
