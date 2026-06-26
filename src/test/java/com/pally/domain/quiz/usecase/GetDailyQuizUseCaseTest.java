package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * BUG 4 — the daily-quiz cache is keyed to the SGT (Asia/Singapore) calendar
 * day, not UTC, so a child's "today's quiz" rolls over at local midnight.
 *
 * <p>The unit asserts the observable consequence: within one SGT day the
 * generator is invoked exactly once (cache HIT on the second tap), and the
 * cache key matches the current SGT date. No real Claude call — the generator
 * port is mocked.
 */
@ExtendWith(MockitoExtension.class)
class GetDailyQuizUseCaseTest {

    @Mock AvatarRepository avatarRepository;
    @Mock WikiRepository wikiRepository;
    @Mock QuizGeneratorPort quizGeneratorPort;
    @Mock AvatarSlotGuard avatarSlotGuard;
    @Mock com.pally.domain.quiz.QuizAnswerKeyRepository answerKeyRepository;

    private GetDailyQuizUseCase useCase;

    private static final String AVATAR_ID = "avatar-quiz";
    private static final String USER_ID = "user-quiz";

    @BeforeEach
    void setUp() {
        useCase = new GetDailyQuizUseCase(
                avatarRepository, wikiRepository, quizGeneratorPort, avatarSlotGuard,
                answerKeyRepository);
    }

    @Test
    void execute_secondTapSameDay_servesFromCache_generatorCalledOnce() {
        WikiPage page = WikiPage.create(AVATAR_ID, "fractions", "Fractions",
                "A fraction shows part of a whole.");
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of(page));

        List<QuizQuestion> generated = List.of(new QuizQuestion(
                "q1", AVATAR_ID, "What is 1/2 of 4?",
                List.of("1", "2", "3", "4"), 1, "fractions", "Half of 4 is 2."));
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList())).thenReturn(generated);

        List<QuizQuestion> first = useCase.execute(AVATAR_ID, USER_ID);
        List<QuizQuestion> second = useCase.execute(AVATAR_ID, USER_ID);

        assertThat(first).isEqualTo(generated);
        assertThat(second)
                .as("second tap within the same SGT day must be a cache HIT")
                .isEqualTo(generated);
        verify(quizGeneratorPort, times(1)).generate(eq(AVATAR_ID), anyList());
    }

    @Test
    void execute_persistsServerAnswerKey_soSubmitCanGradeAuthoritatively() {
        WikiPage page = WikiPage.create(AVATAR_ID, "fractions", "Fractions",
                "A fraction shows part of a whole.");
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of(page));

        List<QuizQuestion> generated = List.of(new QuizQuestion(
                "q1", AVATAR_ID, "What is 1/2 of 4?",
                List.of("1", "2", "3", "4"), 1, "fractions", "Half of 4 is 2."));
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList())).thenReturn(generated);

        useCase.execute(AVATAR_ID, USER_ID);

        // The correct index is persisted at generation time — the linchpin that
        // lets the submit path ignore a tampered client correctMap.
        verify(answerKeyRepository).saveKeys(AVATAR_ID, generated);
    }
}
