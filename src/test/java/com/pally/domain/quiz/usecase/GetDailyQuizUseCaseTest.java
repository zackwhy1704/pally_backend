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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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

    @Test
    void execute_concurrentFirstTaps_coalesceIntoOneGeneration() throws Exception {
        // The stampede: many students of a shared class avatar tap Quiz at the
        // same moment, cache cold. Per-instance single-flight must collapse the
        // concurrent first-taps into ONE generation, not one Claude call each.
        WikiPage page = WikiPage.create(AVATAR_ID, "fractions", "Fractions",
                "A fraction shows part of a whole.");
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of(page));
        List<QuizQuestion> generated = List.of(new QuizQuestion(
                "q1", AVATAR_ID, "What is 1/2 of 4?",
                List.of("1", "2", "3", "4"), 1, "fractions", "Half of 4 is 2."));

        CountDownLatch generatorEntered = new CountDownLatch(1);
        CountDownLatch releaseGenerator = new CountDownLatch(1);
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList())).thenAnswer(inv -> {
            // Leader is now inside generation and holds the in-flight slot.
            generatorEntered.countDown();
            releaseGenerator.await(5, TimeUnit.SECONDS);
            return generated;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<QuizQuestion>> leader = pool.submit(() -> useCase.execute(AVATAR_ID, USER_ID));
            // Wait until the leader is provably inside generate() (slot held).
            assertThat(generatorEntered.await(5, TimeUnit.SECONDS)).isTrue();
            // Second concurrent tap arrives while the leader is still generating;
            // it must coalesce onto the leader, not start its own generation.
            Future<List<QuizQuestion>> follower = pool.submit(() -> useCase.execute(AVATAR_ID, USER_ID));
            Thread.sleep(200); // let the follower reach the coalesced wait
            releaseGenerator.countDown();

            assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo(generated);
            assertThat(follower.get(5, TimeUnit.SECONDS)).isEqualTo(generated);
        } finally {
            pool.shutdownNow();
        }

        // The invariant: exactly ONE generation despite two concurrent first-taps.
        verify(quizGeneratorPort, times(1)).generate(eq(AVATAR_ID), anyList());
    }
}
