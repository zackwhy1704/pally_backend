package com.pally.integration;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.QuizService;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.domain.quiz.usecase.GetDailyQuizUseCase;
import com.pally.domain.weakness.WeaknessProfileService;
import com.pally.domain.weakness.WeaknessStateStore;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.infrastructure.ai.TopicRouter;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaEntity;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * SMOKE TEST — proves the WEAKNESS LOOP closes end-to-end through real services
 * + a real (Testcontainers) Postgres, with the pilot flag ON. This proves the
 * SERVER loop; it is NOT unit logic (that's {@code GetDailyQuizUseCaseTest}).
 *
 * <p><b>The one thing that makes this test honest</b>: it drives the REAL write
 * path. The verified write chain is:
 * <pre>
 *   module completion → updateMastery (per-topic correct-ratios in
 *   quiz_question_results) → onMasteryUpdated (reads findTopicMastery → weak-slug
 *   threshold → stateStore.upsert) → weakSlugsFor returns the weak slugs
 * </pre>
 * So the test seeds the REAL upstream {@code quiz_question_results} (what
 * {@code findTopicMastery} reads — verified: {@code WeaknessSignalRepositoryAdapter}
 * → {@code findAllTopicMasteryByAvatar}) and invokes the REAL
 * {@link WeaknessProfileService#onMasteryUpdated}. It never seeds
 * {@code WeaknessState} directly — doing so would green-wash the one arc nothing
 * else covers (the mastery→weak-slug computation).
 *
 * <p><b>Ceiling (must not be mistaken for full proof)</b>: the client half of
 * the loop — Flutter's {@code topicMap ← sourcePage} that feeds the mastery
 * signal in production — cannot be exercised by any backend test. A green result
 * here plus a broken client mapping still ships a dead loop on device. The real
 * finish line is a manual device check: a student answers a topic wrong and
 * their next quiz visibly leads with it.
 */
@TestPropertySource(properties = "weakness.profile.enabled=true")
class WeaknessLoopSmokeTest extends IntegrationTestBase {

    @Autowired QuizService quizService;
    @Autowired GetDailyQuizUseCase getDailyQuizUseCase;
    @Autowired WeaknessProfileService weaknessProfileService;
    @Autowired WeaknessStateStore weaknessStateStore;
    @Autowired WikiRepository wikiRepository;
    @Autowired QuizQuestionResultJpaRepository quizResultRepo;
    @Autowired AvatarRepository avatarRepository;
    @Autowired ClaudeContextAssembler contextAssembler;

    /**
     * Mock the quiz generator: (1) avoids a real Claude quiz-gen call, and (2)
     * lets us CAPTURE the page pool the real {@link GetDailyQuizUseCase} selected
     * — that captured order IS the weak-first proof. The stub echoes one question
     * per page (sourcePageSlug = page slug) so {@code serveGradable} + answer-key
     * persistence run for real.
     */
    @MockBean QuizGeneratorPort quizGeneratorPort;

    /** Mock the topic router (a fast Claude call) so chat-context assembly is
     *  deterministic + offline; the weakness block under test doesn't use it. */
    @MockBean TopicRouter topicRouter;

    private static final Subject SUBJECT = Subject.MATHS;

    /**
     * Six topics with DISTINCT certainty scores chosen so the weak topics
     * (fractions, decimals) are NOT quiz-first by default: the baseline order
     * (certainty ASC) puts them LAST, and decimals falls outside the top-5 pool
     * entirely. Weak-first must therefore visibly pull both to the front — a
     * strong, readable before/after.
     */
    private record Topic(String slug, double certainty) {}
    private static final List<Topic> TOPICS = List.of(
            new Topic("algebra", 0.10),
            new Topic("geometry", 0.20),
            new Topic("ratios", 0.30),
            new Topic("percentages", 0.40),
            new Topic("fractions", 0.80),
            new Topic("decimals", 0.90));
    // Baseline top-5 pool = certainty ASC, decimals (0.90) excluded as the 6th.
    private static final List<String> BASELINE_TOP5 =
            List.of("algebra", "geometry", "ratios", "percentages", "fractions");

    @BeforeEach
    void stubsAndGate() {
        // Generator echo: one question per page, carrying sourcePageSlug.
        lenient().when(quizGeneratorPort.generate(anyString(), anyList(), anyString())).thenAnswer(inv -> {
            String avId = inv.getArgument(0);
            List<WikiPage> pages = inv.getArgument(1);
            return pages.stream().map(p -> new QuizQuestion(
                    IdGenerator.newId(), avId, "Q: " + p.getSlug(),
                    List.of("a", "b", "c", "d"), 0, p.getSlug(), "because", null, null)).toList();
        });
        lenient().when(topicRouter.route(any(), any(), any())).thenReturn(List.of());
        // onMasteryUpdated → rebuildFor → compileWithTier: keep it a clean no-op so
        // the async rebuild never NPEs on a null CompileOutput. The WeaknessState
        // upsert happens BEFORE rebuildFor, so this doesn't affect the write proof.
        lenient().when(wikiCompilerPort.compileWithTier(any(), anyList(), anyList()))
                .thenReturn(new WikiCompilerPort.CompileOutput(List.of(), "test"));

        // SETUP GUARD — non-negotiable. If the pilot flag is off, every arc returns
        // empty and this whole test would "prove" a dead loop that is merely
        // disabled. Fail loudly here before any proof runs.
        assertThat(weaknessProfileService.isEnabled())
                .as("weakness.profile.enabled MUST be true for this smoke test to mean "
                        + "anything — set it via @TestPropertySource. If false, the loop is "
                        + "inert and every assertion below is a false negative.")
                .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════
    // WRITE-PATH PROOF — the arc nothing else covers. Drive the REAL
    // onMasteryUpdated off seeded quiz_question_results; assert weakSlugsFor
    // computes the weak set. (Seeding WeaknessState directly would skip exactly
    // this computation — the shortcut this test exists to refuse.)
    // ════════════════════════════════════════════════════════════════════════
    @Test
    void writePath_realOnMasteryUpdated_computesWeakSlugsFromMastery() {
        Student a = newPersonalStudent("writepath");
        seedPages(a.avatarId);

        assertThat(weaknessProfileService.weakSlugsFor(a.userId, SUBJECT))
                .as("no mastery yet → no weak slugs")
                .isEmpty();

        // Student got fractions + decimals wrong (ratio 0, >= MIN_ATTEMPTS), algebra right.
        seedMastery(a, "fractions", 0, 2);
        seedMastery(a, "decimals", 0, 2);
        seedMastery(a, "algebra", 2, 0);

        driveWeaknessAndAwait(a);

        List<String> weak = weaknessProfileService.weakSlugsFor(a.userId, SUBJECT);
        System.out.println("[SMOKE] write-path weakSlugsFor(" + a.userId + ") = " + weak);
        assertThat(weak)
                .as("real onMasteryUpdated must compute {fractions, decimals} from the "
                        + "seeded quiz_question_results — NOT from a directly-seeded WeaknessState")
                .containsExactlyInAnyOrder("fractions", "decimals");
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROOF 1 — the headline: a student weak on X gets an X-first quiz.
    // ════════════════════════════════════════════════════════════════════════
    @Test
    void proof1_quizPool_movesWeakTopicsToFront_afterRealWrite() {
        Student a = newPersonalStudent("proof1");
        seedPages(a.avatarId);

        List<String> before = capturePool(a);
        System.out.println("[SMOKE] proof1 BEFORE pool = " + before);
        assertThat(before)
                .as("before any weakness the pool is the certainty/coverage order")
                .containsExactlyElementsOf(BASELINE_TOP5);

        seedMastery(a, "fractions", 0, 2);
        seedMastery(a, "decimals", 0, 2);
        driveWeaknessAndAwait(a);

        List<String> after = capturePool(a);
        System.out.println("[SMOKE] proof1 AFTER  pool = " + after);
        assertThat(after.subList(0, 2))
                .as("the two weak topics must lead the next quiz")
                .containsExactlyInAnyOrder("fractions", "decimals");
        assertThat(after)
                .as("decimals was OUTSIDE the baseline top-5 and must now be pulled INTO the pool")
                .contains("decimals");
        assertThat(before).doesNotContain("decimals");
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROOF 2 — per-student isolation + the B2B namespace at runtime. Two class
    // students over the SAME corpus, weak on different topics, get different
    // quizzes. If the class namespace (weak topicSlug vs corpus WikiPage slug)
    // did NOT match, both would fall back to identical certainty order and the
    // "different" assertion would fail — so this IS the runtime namespace proof.
    // ════════════════════════════════════════════════════════════════════════
    @Test
    void proof2_classStudents_differentWeakness_yieldDifferentQuizzes_overSharedCorpus() {
        // Shared class corpus (owned by a throwaway teacher user), with the pages.
        String corpusOwner = registerConsentedUser(
                "corpus-owner-" + System.nanoTime() + "@test.com", "password123").userId();
        Avatar corpus = avatarRepository.save(
                Avatar.create(corpusOwner, "ClassCorpus", SUBJECT, CharacterType.MOCHI));
        seedPages(corpus.getId());

        Student s1 = newClassStudent("proof2-s1", corpus.getId());
        Student s2 = newClassStudent("proof2-s2", corpus.getId());

        // Mastery is recorded on the STUDENT avatar (per prod: quiz submits write
        // quiz_question_results with avatarId = student avatar, topicSlug = the
        // corpus page slug the question came from).
        seedMastery(s1, "geometry", 0, 2);
        seedMastery(s1, "ratios", 0, 2);
        driveWeaknessAndAwait(s1);

        seedMastery(s2, "algebra", 0, 2);
        seedMastery(s2, "percentages", 0, 2);
        driveWeaknessAndAwait(s2);

        List<String> p1 = capturePool(s1);
        List<String> p2 = capturePool(s2);
        System.out.println("[SMOKE] proof2 class S1 pool = " + p1);
        System.out.println("[SMOKE] proof2 class S2 pool = " + p2);

        assertThat(p1.subList(0, 2))
                .as("class student S1 (weak geometry/ratios) — corpus slugs must match S1's "
                        + "weak topicSlugs at runtime, else weak-first silently no-ops for B2B")
                .containsExactlyInAnyOrder("geometry", "ratios");
        assertThat(p2.subList(0, 2))
                .as("class student S2 (weak algebra/percentages) over the SAME corpus")
                .containsExactlyInAnyOrder("algebra", "percentages");
        assertThat(p1.subList(0, 2))
                .as("two students, same corpus, different weakness → different quizzes")
                .isNotEqualTo(p2.subList(0, 2));
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROOF 3 — behaviour preserved: a fresh student (no mastery) gets exactly
    // today's certainty/coverage order, no reorder.
    // ════════════════════════════════════════════════════════════════════════
    @Test
    void proof3_freshStudent_getsUnchangedCertaintyOrder() {
        Student c = newPersonalStudent("proof3");
        seedPages(c.avatarId);

        assertThat(weaknessProfileService.weakSlugsFor(c.userId, SUBJECT)).isEmpty();
        List<String> pool = capturePool(c);
        System.out.println("[SMOKE] proof3 fresh pool = " + pool);
        assertThat(pool)
                .as("no profile → the quiz is unchanged from today's certainty/coverage order")
                .containsExactlyElementsOf(BASELINE_TOP5);
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROOF 4 — the student-facing DISPLAY consumer (focusFor) reports the real
    // weak areas. The teacher roster (TeacherWeaknessService.perStudentWeakness)
    // reads the IDENTICAL weakSlugsFor(sid, subject) signal (source L63); it is
    // not stood up here because doing so exercises org/class/enrollment/ownership
    // plumbing, not the weakness loop — that would test centre-access wiring, not
    // adaptivity. focusFor proves the display-consumer contract end-to-end.
    // ════════════════════════════════════════════════════════════════════════
    @Test
    void proof4_focusFor_reportsTheRealWeakAreas() {
        Student a = newPersonalStudent("proof4");
        seedPages(a.avatarId);
        seedMastery(a, "fractions", 0, 2);
        seedMastery(a, "decimals", 0, 2);
        driveWeaknessAndAwait(a);

        Map<String, Object> focus = weaknessProfileService.focusFor(a.userId, SUBJECT);
        System.out.println("[SMOKE] proof4 focusFor = " + focus);
        assertThat(focus.get("enabled")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> areas = (List<Map<String, Object>>) focus.get("focusAreas");
        assertThat(areas)
                .as("focusFor surfaces exactly the two weak areas (reads weakSlugsFor, "
                        + "not all pages)")
                .hasSize(2);
    }

    // ════════════════════════════════════════════════════════════════════════
    // PROOF 5 — chat GROUNDS on weakness, BY DESIGN. Chat uses weaknessPagesFor
    // (compiled weakness-profile PROSE bodies) — the correct signal for grounding
    // — NOT weakSlugsFor (bare display slugs, unusable as prose). This is a
    // deliberate grounding-vs-display split, documented at weakSlugsFor's own
    // contract. We assert chat grounds on the weakness prose and DO NOT flag the
    // difference as a defect, because source says it isn't one.
    // ════════════════════════════════════════════════════════════════════════
    @Test
    void proof5_chatContext_groundsOnWeaknessProse_byDesign() {
        Student a = newPersonalStudent("proof5");
        seedPages(a.avatarId);

        // Seed the student's compiled weakness-profile avatar directly (in prod
        // rebuildFor compiles it; here the compiler is mocked). This is the prose
        // buildWeaknessBlock renders.
        Avatar profile = Avatar.create(a.userId, "Maths Weakness Profile", SUBJECT, CharacterType.MOCHI);
        profile.markWeaknessProfile();
        avatarRepository.save(profile);
        wikiRepository.save(WikiPage.create(profile.getId(), "fractions",
                "Fractions — a known struggle",
                "This student mixes up numerator and denominator when adding fractions."));

        Avatar personal = avatarRepository.findById(a.avatarId).orElseThrow();
        List<WikiPage> activePages = wikiRepository.findActiveByAvatarId(a.avatarId);
        // buildWeaknessBlock is emitted into the cache BLOCKS (assembleSystemBlocks),
        // not the plain systemPrompt string — that's the real chat-context path.
        List<Map<String, Object>> blocks = contextAssembler.assembleSystemBlocks(personal, activePages);
        String allBlockText = blocks.stream()
                .map(b -> String.valueOf(b.get("text")))
                .reduce("", (x, y) -> x + "\n" + y);
        System.out.println("[SMOKE] proof5 weakness-in-context = "
                + allBlockText.contains("STRUGGLES WITH"));

        assertThat(allBlockText)
                .as("chat DOES ground on the student's weakness — via prose bodies "
                        + "(weaknessPagesFor), the correct signal for a tutor to steer on")
                .contains("STRUGGLES WITH")
                .contains("mixes up numerator and denominator");
        // NOTE (by design, do NOT assert otherwise): chat uses weaknessPagesFor
        // (prose) not weakSlugsFor (slugs). weakSlugsFor's contract scopes itself to
        // the quiz + teacher DISPLAY surfaces; chat needs page bodies to ground on.
        // A failing/soft assertion about this "mismatch" would be WRONG.
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record Student(String userId, String avatarId) {}

    /** A B2C personal-avatar student (avatar created via the real endpoint → active). */
    private Student newPersonalStudent(String tag) {
        AuthResult u = registerConsentedUser(
                tag + "-" + System.nanoTime() + "@test.com", "password123");
        ResponseEntity<Map> resp = post("/api/v1/avatars", u.token(),
                Map.of("name", "MathBot", "subject", "MATHS", "characterType", "MOCHI"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String avatarId = (String) ((Map<String, Object>) resp.getBody().get("data")).get("id");
        return new Student(u.userId(), avatarId);
    }

    /** A B2B class student whose avatar reads a shared corpus (has no wiki of its own). */
    private Student newClassStudent(String tag, String corpusAvatarId) {
        String userId = registerConsentedUser(
                tag + "-" + System.nanoTime() + "@test.com", "password123").userId();
        Avatar student = Avatar.create(userId, "ClassKid", SUBJECT, CharacterType.MOCHI);
        student.setCorpusAvatarId(corpusAvatarId);
        avatarRepository.save(student);
        return new Student(userId, student.getId());
    }

    private void seedPages(String pageAvatarId) {
        for (Topic t : TOPICS) {
            wikiRepository.save(WikiPage.reconstitute(
                    IdGenerator.newId(), pageAvatarId, t.slug(),
                    t.slug(), "Notes about " + t.slug() + ".",
                    WikiPage.Certainty.INFERRED, Instant.now(),
                    50, null, null, false,
                    null, 0, t.certainty(),
                    WikiPage.Status.ACTIVE, false, null));
        }
    }

    /** Seed the REAL upstream findTopicMastery reads: quiz_question_results rows. */
    private void seedMastery(Student s, String slug, int correct, int wrong) {
        for (int i = 0; i < correct + wrong; i++) {
            QuizQuestionResultJpaEntity r = new QuizQuestionResultJpaEntity();
            r.setId(IdGenerator.newId());
            r.setUserId(s.userId());
            r.setAvatarId(s.avatarId());
            r.setQuestionId("q-" + slug + "-" + i + "-" + System.nanoTime());
            r.setTopicSlug(slug);
            r.setWasCorrect(i < correct);
            r.setCreatedAt(Instant.now());
            quizResultRepo.save(r);
        }
    }

    /** Invoke the REAL (async, best-effort) write trigger and wait for the upsert. */
    private void driveWeaknessAndAwait(Student s) {
        weaknessProfileService.onMasteryUpdated(s.userId(), s.avatarId());
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            if (weaknessStateStore.find(s.userId(), SUBJECT).isPresent()) return;
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        throw new AssertionError("onMasteryUpdated did not persist a WeaknessState for "
                + s.userId() + " within 8s — the async write path did not run");
    }

    /**
     * Ask the REAL {@link QuizService#getDailyQuiz} for the student's quiz and
     * return the page-pool slug order the real {@link GetDailyQuizUseCase}
     * selected (captured off the generator). Clears the per-(avatar,day) cache
     * first so each call re-generates — the only way to observe a before/after on
     * the same student, since there is no public cache-evict seam.
     */
    private List<String> capturePool(Student s) {
        clearQuizCache();
        clearInvocations(quizGeneratorPort);
        quizService.getDailyQuiz(s.userId(), s.avatarId());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<WikiPage>> cap = ArgumentCaptor.forClass(List.class);
        verify(quizGeneratorPort).generate(eq(s.avatarId()), cap.capture(), anyString());
        return cap.getValue().stream().map(WikiPage::getSlug).toList();
    }

    /** Reflectively clear the in-memory daily-quiz cache (no public evict seam;
     *  simulates the day rollover the prod cache keys on). */
    private void clearQuizCache() {
        try {
            Field f = GetDailyQuizUseCase.class.getDeclaredField("dailyCache");
            f.setAccessible(true);
            ((Map<?, ?>) f.get(getDailyQuizUseCase)).clear();
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("could not clear dailyCache — field renamed?", e);
        }
    }
}
