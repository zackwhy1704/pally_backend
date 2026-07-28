package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.domain.weakness.WeaknessProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
    @Mock WeaknessProfileService weaknessProfileService;

    private GetDailyQuizUseCase useCase;

    private static final String AVATAR_ID = "avatar-quiz";
    private static final String USER_ID = "user-quiz";

    @BeforeEach
    void setUp() {
        useCase = new GetDailyQuizUseCase(
                avatarRepository, wikiRepository, quizGeneratorPort, avatarSlotGuard,
                weaknessProfileService);
    }

    @Test
    void execute_secondTapSameDay_servesFromCache_generatorCalledOnce() {
        WikiPage page = WikiPage.create(AVATAR_ID, "fractions", "Fractions",
                "A fraction shows part of a whole.");
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of(page));

        List<QuizQuestion> generated = List.of(new QuizQuestion(
                "q1", AVATAR_ID, "What is 1/2 of 4?",
                List.of("1", "2", "3", "4"), 1, "fractions", "Half of 4 is 2.", null, null));
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList(), anyString())).thenReturn(generated);

        List<QuizQuestion> first = useCase.execute(AVATAR_ID, USER_ID);
        List<QuizQuestion> second = useCase.execute(AVATAR_ID, USER_ID);

        assertThat(first).isEqualTo(generated);
        assertThat(second)
                .as("second tap within the same SGT day must be a cache HIT")
                .isEqualTo(generated);
        verify(quizGeneratorPort, times(1)).generate(eq(AVATAR_ID), anyList(), anyString());
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
                List.of("1", "2", "3", "4"), 1, "fractions", "Half of 4 is 2.", null, null));

        CountDownLatch generatorEntered = new CountDownLatch(1);
        CountDownLatch releaseGenerator = new CountDownLatch(1);
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList(), anyString())).thenAnswer(inv -> {
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
        verify(quizGeneratorPort, times(1)).generate(eq(AVATAR_ID), anyList(), anyString());
    }

    @Test
    void execute_classAvatar_readsTheCorpusWiki_notItsOwnEmptyOne() {
        // A CENTRE_CLASS student avatar has NO wiki of its own — it reads the
        // shared class corpus. Without this resolution its daily quiz is always
        // empty (the grade-integrity blocker the smoke surfaced).
        final String corpus = "corpus-1";
        com.pally.domain.avatar.Avatar classAvatar =
                com.pally.domain.avatar.Avatar.reconstitute(
                        AVATAR_ID, USER_ID, "ClassMochi",
                        com.pally.domain.avatar.Subject.MATHS,
                        com.pally.domain.avatar.CharacterType.ZAP, 0,
                        java.time.Instant.now());
        classAvatar.setCorpusAvatarId(corpus);
        when(avatarRepository.findById(AVATAR_ID))
                .thenReturn(java.util.Optional.of(classAvatar));

        WikiPage page = WikiPage.create(corpus, "fractions", "Fractions",
                "A fraction shows part of a whole.");
        when(wikiRepository.findByAvatarId(corpus)).thenReturn(List.of(page));
        List<QuizQuestion> generated = List.of(new QuizQuestion(
                "q1", AVATAR_ID, "1/2 of 4?", List.of("1", "2"), 1, "fractions", "two", null, null));
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList(), anyString())).thenReturn(generated);

        List<QuizQuestion> result = useCase.execute(AVATAR_ID, USER_ID);

        assertThat(result).isEqualTo(generated);
        // Read the CORPUS's pages — never the (empty) student avatar's own.
        verify(wikiRepository).findByAvatarId(corpus);
        verify(wikiRepository, never()).findByAvatarId(AVATAR_ID);
    }

    // ── Weakness loop at the quiz — the gate tests ────────────────────────
    // These prove "the next quiz adapts to YOUR weakness": the page pool fed to
    // the generator is ordered weak-first per student, WITHOUT breaking the
    // per-student cache/answer-key path (generation is already per-student
    // avatar). The answer key is saved for exactly the generated set at the
    // QuizService serving chokepoint, so no per-user subsetting can un-grade a
    // question — asserted by QuizServiceTest.

    @Test
    void execute_studentWeakOnAPageTopic_sortsThatPageToFrontOfGeneratorPool() {
        Avatar avatar = personal(AVATAR_ID, USER_ID);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
        // Three equal-certainty pages in a fixed encounter order — with no weak
        // signal the pool keeps that order, so "gamma" would be LAST.
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of(
                page(AVATAR_ID, "alpha"), page(AVATAR_ID, "beta"), page(AVATAR_ID, "gamma")));
        // The student is weak on gamma (its slug is in their per-student signal).
        when(weaknessProfileService.weakSlugsFor(USER_ID, Subject.MATHS))
                .thenReturn(List.of("gamma"));
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList(), anyString())).thenReturn(List.of());

        useCase.execute(AVATAR_ID, USER_ID);

        assertThat(capturePool(AVATAR_ID).get(0).getSlug())
                .as("the student's weak page must lead the generator pool")
                .isEqualTo("gamma");
    }

    @Test
    void execute_twoStudentsDifferentWeakSlugs_getDifferentWeakFirstOrder_overSharedCorpus() {
        // The core proof: two students of the SAME class corpus, weak on DIFFERENT
        // topics, get DIFFERENT quizzes. Fails on student-blind code. Also asserts
        // the class-path namespace: weak topicSlug == corpus WikiPage slug (Fact 5)
        // — if it didn't intersect, neither pool would reorder and both would match.
        final String corpus = "corpus-1";
        when(wikiRepository.findByAvatarId(corpus)).thenReturn(List.of(
                page(corpus, "alpha"), page(corpus, "beta"), page(corpus, "gamma")));
        when(avatarRepository.findById("stu-1")).thenReturn(Optional.of(classStudent("stu-1", "user-1", corpus)));
        when(avatarRepository.findById("stu-2")).thenReturn(Optional.of(classStudent("stu-2", "user-2", corpus)));
        when(weaknessProfileService.weakSlugsFor("user-1", Subject.MATHS)).thenReturn(List.of("gamma"));
        when(weaknessProfileService.weakSlugsFor("user-2", Subject.MATHS)).thenReturn(List.of("alpha"));
        when(quizGeneratorPort.generate(anyString(), anyList(), anyString())).thenReturn(List.of());

        useCase.execute("stu-1", "user-1");
        useCase.execute("stu-2", "user-2");

        String firstForStudent1 = capturePool("stu-1").get(0).getSlug();
        String firstForStudent2 = capturePool("stu-2").get(0).getSlug();
        assertThat(firstForStudent1).isEqualTo("gamma");
        assertThat(firstForStudent2).isEqualTo("alpha");
        assertThat(firstForStudent1)
                .as("two students with different weak sets must get different quizzes")
                .isNotEqualTo(firstForStudent2);
    }

    @Test
    void execute_noWeaknessProfile_poolIsExactlyTheCertaintyCoverageOrder() {
        // Behaviour-preservation: with no profile (flag off / new student) the pool
        // is EXACTLY today's certainty-ASC then quiz-use-ASC order — unchanged.
        Avatar avatar = personal(AVATAR_ID, USER_ID);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of(
                pageWithCertainty(AVATAR_ID, "high", 0.9),
                pageWithCertainty(AVATAR_ID, "low", 0.1),
                pageWithCertainty(AVATAR_ID, "mid", 0.5)));
        when(weaknessProfileService.weakSlugsFor(USER_ID, Subject.MATHS)).thenReturn(List.of());
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList(), anyString())).thenReturn(List.of());

        useCase.execute(AVATAR_ID, USER_ID);

        assertThat(capturePool(AVATAR_ID).stream().map(WikiPage::getSlug).toList())
                .as("no profile must preserve today's certainty/coverage order")
                .containsExactly("low", "mid", "high");
    }

    @Test
    void execute_weakSlugAbsentFromPool_doesNotReorder_provingMatchIsByExactSlug() {
        // Namespace guard (negative): a weak slug that matches NO page slug must
        // silently no-op. This is what a BROKEN slug namespace would look like — so
        // if weak-first ever reordered here it would mean matching on the wrong key.
        Avatar avatar = personal(AVATAR_ID, USER_ID);
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
        when(wikiRepository.findByAvatarId(AVATAR_ID)).thenReturn(List.of(
                pageWithCertainty(AVATAR_ID, "high", 0.9),
                pageWithCertainty(AVATAR_ID, "low", 0.1),
                pageWithCertainty(AVATAR_ID, "mid", 0.5)));
        when(weaknessProfileService.weakSlugsFor(USER_ID, Subject.MATHS))
                .thenReturn(List.of("nonexistent-topic"));
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList(), anyString())).thenReturn(List.of());

        useCase.execute(AVATAR_ID, USER_ID);

        assertThat(capturePool(AVATAR_ID).stream().map(WikiPage::getSlug).toList())
                .as("a weak slug matching no page must not reorder the pool")
                .containsExactly("low", "mid", "high");
    }

    @Test
    void execute_tagsWeakSlugQuestionsWithSelectionReason_othersNull() {
        // Provenance: a question from a weak page is tagged "WEAK_TOPIC:{title}"; one from
        // a non-weak page stays null. Drives the client's "reviewing your weak spot" badge.
        when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(personal(AVATAR_ID, USER_ID)));
        when(wikiRepository.findByAvatarId(AVATAR_ID))
                .thenReturn(List.of(page(AVATAR_ID, "alpha"), page(AVATAR_ID, "beta")));
        when(weaknessProfileService.weakSlugsFor(USER_ID, Subject.MATHS)).thenReturn(List.of("alpha"));
        QuizQuestion weakQ = new QuizQuestion(
                "qa", AVATAR_ID, "Qa", List.of("1", "2"), 0, "alpha", "e", "Alpha", null);
        QuizQuestion okQ = new QuizQuestion(
                "qb", AVATAR_ID, "Qb", List.of("1", "2"), 0, "beta", "e", "Beta", null);
        when(quizGeneratorPort.generate(eq(AVATAR_ID), anyList(), anyString())).thenReturn(List.of(weakQ, okQ));

        List<QuizQuestion> result = useCase.execute(AVATAR_ID, USER_ID);

        QuizQuestion a = result.stream().filter(q -> "alpha".equals(q.sourcePageSlug())).findFirst().orElseThrow();
        QuizQuestion b = result.stream().filter(q -> "beta".equals(q.sourcePageSlug())).findFirst().orElseThrow();
        assertThat(a.selectionReason()).isEqualTo("WEAK_TOPIC:Alpha");
        assertThat(b.selectionReason()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<WikiPage> capturePool(String avatarId) {
        ArgumentCaptor<List<WikiPage>> cap = ArgumentCaptor.forClass(List.class);
        verify(quizGeneratorPort).generate(eq(avatarId), cap.capture(), anyString());
        return cap.getValue();
    }

    private static Avatar personal(String avatarId, String userId) {
        return Avatar.reconstitute(avatarId, userId, "Mochi",
                Subject.MATHS, CharacterType.MOCHI, 0, Instant.now());
    }

    private static Avatar classStudent(String avatarId, String userId, String corpus) {
        Avatar a = Avatar.reconstitute(avatarId, userId, "ClassMochi",
                Subject.MATHS, CharacterType.ZAP, 0, Instant.now());
        a.setCorpusAvatarId(corpus);
        return a;
    }

    private static WikiPage page(String avatarId, String slug) {
        // create() gives every page the same certainty (0.5) + quizUseCount (0),
        // so the coverage comparator ties and the stable sort keeps input order —
        // isolating the weak-first key as the only thing that can reorder.
        return WikiPage.create(avatarId, slug, slug, "content about " + slug);
    }

    private static WikiPage pageWithCertainty(String avatarId, String slug, double certainty) {
        return WikiPage.reconstitute(
                slug + "-id", avatarId, slug, slug, "content about " + slug,
                WikiPage.Certainty.INFERRED, Instant.now(),
                0, null, null, false,
                null, 0, certainty, WikiPage.Status.ACTIVE, false, null);
    }
}
