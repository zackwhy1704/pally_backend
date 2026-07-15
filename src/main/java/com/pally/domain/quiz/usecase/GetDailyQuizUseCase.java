package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import com.pally.domain.weakness.WeaknessProfileService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class GetDailyQuizUseCase {

    private static final Logger log = LoggerFactory.getLogger(GetDailyQuizUseCase.class);

    /// Cap on pages fed to the quiz generator per request. Five questions ~=
    /// five pages keeps the prompt small enough for Haiku and forces the
    /// "prioritise weak material" bias to actually matter.
    private static final int MAX_PAGES_PER_QUIZ = 5;

    /// Day boundary for the daily-quiz cache key + eviction. SGT (not UTC) so a
    /// child's "today's quiz" rolls over at local midnight, matching the rest of
    /// the app's day boundary (XpService, ActivityLogService, ProgressController).
    private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

    private final AvatarRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final QuizGeneratorPort quizGeneratorPort;
    private final AvatarSlotGuard avatarSlotGuard;
    private final WeaknessProfileService weaknessProfileService;

    /// In-memory daily quiz cache: avatarId → (date → questions).
    /// Single Railway instance → no distributed state needed. Cache evicts
    /// naturally when the date rolls over (new key = no hit). A redeploy
    /// clears it — users get a fresh quiz on first tap after restart, which
    /// is acceptable. This avoids calling Claude repeatedly when the user
    /// taps Quiz multiple times in a session.
    private final Map<String, Map<LocalDate, List<QuizQuestion>>> dailyCache =
            new ConcurrentHashMap<>();

    /// PER-INSTANCE single-flight for quiz generation, keyed by (avatarId|date).
    /// SCOPE — read this honestly: this coalesces concurrent first-taps for the
    /// SAME quiz WITHIN ONE JVM into a single Claude call. It does NOT coordinate
    /// across instances: behind N app instances the worst case is N concurrent
    /// generations for the same key (one leader per instance), not 1 — still a
    /// huge cut from "every simultaneous student triggers their own call" (the
    /// stampede when 100 kids of a shared class avatar tap Quiz at 4pm). True
    /// GLOBAL single-flight needs a distributed lock (Postgres advisory lock on
    /// hash(avatarId|date), or Redis) — tracked as a follow-up, not built here.
    private final Map<String, CompletableFuture<List<QuizQuestion>>> inFlight =
            new ConcurrentHashMap<>();

    /// How long a coalesced follower waits for the leader's generation before
    /// giving up and generating independently. Matches the client receive
    /// timeout (90s) so a follower never hangs past what the request allows.
    private static final long FOLLOWER_WAIT_SECONDS = 90;

    public List<QuizQuestion> execute(String avatarId, String userId) {
        // Fix 2: Slot guard — locked avatars cannot be quizzed.
        avatarSlotGuard.requireActive(avatarId, userId);

        // Return today's cached quiz if it was already generated — avoids a
        // repeated Claude call when the user taps Quiz multiple times per session.
        LocalDate today = LocalDate.now(SGT);
        Map<LocalDate, List<QuizQuestion>> avatarCache =
                dailyCache.computeIfAbsent(avatarId, k -> new ConcurrentHashMap<>());
        List<QuizQuestion> cached = avatarCache.get(today);
        if (cached != null && !cached.isEmpty()) {
            log.info("[Pipeline:Quiz] Cache HIT avatarId={} questions={}", avatarId, cached.size());
            return cached;
        }

        // SINGLE-FLIGHT (per-instance): the first request for this (avatar, day)
        // becomes the leader and runs the one expensive generation; concurrent
        // requests for the SAME quiz await the leader's result instead of each
        // firing their own Claude call. See the field doc for the cross-instance
        // scope caveat.
        String key = avatarId + "|" + today;
        CompletableFuture<List<QuizQuestion>> mine = new CompletableFuture<>();
        CompletableFuture<List<QuizQuestion>> leader = inFlight.putIfAbsent(key, mine);
        if (leader != null) {
            // A generation for this exact quiz is already in flight — wait for it.
            try {
                return leader.get(FOLLOWER_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[Pipeline:Quiz] Coalesced wait failed avatarId={} ({}) — "
                        + "generating independently", avatarId, e.getMessage());
                // Fall through: generate ourselves rather than fail the request.
                // We are NOT the leader, so we never touch the leader's future.
                return generateAndCache(avatarId, userId, today, avatarCache);
            }
        }

        // We are the leader: own the in-flight slot, generate, hand the result to
        // any followers, then release the slot regardless of outcome.
        try {
            List<QuizQuestion> questions = generateAndCache(avatarId, userId, today, avatarCache);
            mine.complete(questions);
            return questions;
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    /// The actual cache-miss generation: pick pages, call the generator, persist
    /// the server answer key, and cache the questions. Extracted so the
    /// single-flight in {@link #execute} wraps exactly one generation.
    private List<QuizQuestion> generateAndCache(
            String avatarId, String userId, LocalDate today,
            Map<LocalDate, List<QuizQuestion>> avatarCache) {
        // A CENTRE_CLASS student avatar has NO wiki of its own — it reads the
        // shared class corpus (Avatar.corpusAvatarId), the same way
        // SendMessageUseCase resolves chat context. Without this, a class
        // student's daily quiz is always empty (the content lives on the corpus).
        // The quiz instance still belongs to the STUDENT avatar (keys, cache,
        // submission, results) — only the page SOURCE resolves to the corpus.
        // NB: because the quiz avatar is per-student (owned 1:1 by userId), this
        // whole generation — cache, single-flight, answer key — is already
        // per-user; the weak-first bias below can therefore use THIS student's
        // own weak signal directly, with no cross-student contamination.
        Avatar avatar = avatarRepository.findById(avatarId).orElse(null);
        String wikiSourceId =
                (avatar != null && avatar.getCorpusAvatarId() != null
                        && !avatar.getCorpusAvatarId().isBlank())
                        ? avatar.getCorpusAvatarId() : avatarId;
        List<WikiPage> allPages = wikiRepository.findByAvatarId(wikiSourceId);
        List<WikiPage> pages = allPages.stream()
                .filter(p -> p.getStatus() == WikiPage.Status.ACTIVE)
                .toList();

        // ── Pipeline log: quiz source ─────────────────────────────────────
        log.info("[Pipeline:Quiz] avatarId={} totalPages={} activePages={}",
                avatarId, allPages.size(), pages.size());

        if (pages.isEmpty()) {
            log.warn("[Pipeline:Quiz] NO ACTIVE wiki pages for avatarId={} — " +
                     "quiz returns empty. Total pages (all statuses)={}. " +
                     "Upload notes and wait for compile, or call /wiki/recompile.",
                     avatarId, allPages.size());
            return List.of();
        }

        // R3 — bias the pool toward the student's weak spots and under-tested
        // material, THEN close the weakness loop: pages the student is actually
        // weak on (from their own quiz history) sort to the FRONT so the day's
        // quiz targets their weak topics. weakSlugsFor is per-(userId, subject)
        // and returns [] when the pilot flag is off or the student has no profile
        // yet — in which case the primary key is constant and this degrades to the
        // exact certainty/coverage order below (behaviour-identical for new
        // students / prod). The stable sort preserves that order within each group.
        //
        // CROSS-REPO CONTRACT (no backend test can guard this): weak slugs match a
        // page only when weakSlugs ∋ WikiPage.getSlug(). Those slugs originate as
        // QuizQuestionResult.topicSlug, which the Flutter client fills on submit as
        // topicMap[q.id] = q.sourcePage (quiz_view_model.dart), i.e. the question's
        // sourcePageSlug == the WikiPage slug it was generated from. If the client
        // ever stops populating topicMap from sourcePage, the slugs stop matching
        // and this loop SILENTLY no-ops (no reorder, no error). Test 4 guards the
        // backend half (exact-slug match, not fuzzy); the client half is uncovered
        // here — keep the two sides coupled.
        Set<String> weakSlugs = new HashSet<>(
                avatar != null && avatar.getSubject() != null
                        ? weaknessProfileService.weakSlugsFor(userId, avatar.getSubject())
                        : List.of());
        Comparator<WikiPage> coverageOrder = Comparator
                .comparingDouble(WikiPage::getCertaintyScore)
                .thenComparingInt(WikiPage::getQuizUseCount);
        Comparator<WikiPage> weakFirst = Comparator
                .comparingInt((WikiPage p) -> weakSlugs.contains(p.getSlug()) ? 0 : 1)
                .thenComparing(coverageOrder);
        List<WikiPage> prioritised = pages.stream()
                .sorted(weakFirst)
                .limit(MAX_PAGES_PER_QUIZ)
                .toList();

        log.info("[Pipeline:Quiz] weak-first bias avatarId={} weakSlugs={} matchedInPool={}",
                avatarId, weakSlugs.size(),
                pages.stream().filter(p -> weakSlugs.contains(p.getSlug())).count());

        log.info("[Pipeline:Quiz] Generating from {} pages: slugs={}",
                prioritised.size(), prioritised.stream().map(WikiPage::getSlug).toList());

        // Record usage on the SOURCE avatar (the corpus, for a class student) so
        // coverage tracking lands where the pages actually live.
        wikiRepository.recordQuizUsage(wikiSourceId,
                prioritised.stream().map(WikiPage::getSlug).toList());

        List<QuizQuestion> questions = quizGeneratorPort.generate(avatarId, prioritised);
        log.info("[Pipeline:Quiz] Generated {} questions for avatarId={}", questions.size(), avatarId);

        // Weak-first provenance: a question drawn from one of the student's weak pages is
        // tagged "WEAK_TOPIC:{concept}" so the client can badge "Reviewing your weak spot".
        // (Metadata only — never reveals the answer. The daily cache stores the tag with it.)
        if (!weakSlugs.isEmpty()) {
            questions = questions.stream()
                    .map(q -> weakSlugs.contains(q.sourcePageSlug())
                            ? q.withSelectionReason("WEAK_TOPIC:"
                                + (q.sourcePageTitle() != null ? q.sourcePageTitle() : q.sourcePageSlug()))
                            : q)
                    .toList();
        }

        // NOTE: the SERVER answer key is persisted at the SERVING chokepoint
        // (QuizService.serveGradable), not here, so every served quiz — cache
        // hit or miss — is guaranteed to have a key. See that method's doc.

        // Store in daily cache so repeat taps are instant
        if (!questions.isEmpty()) {
            avatarCache.put(today, questions);
            // Evict yesterday's entry to keep memory bounded (at most 2 dates per avatar)
            avatarCache.entrySet().removeIf(e -> e.getKey().isBefore(today));
        }
        return questions;
    }
}
