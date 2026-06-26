package com.pally.domain.quiz.usecase;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.usecase.AvatarSlotGuard;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.port.QuizGeneratorPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final QuizAnswerKeyRepository answerKeyRepository;

    /// In-memory daily quiz cache: avatarId → (date → questions).
    /// Single Railway instance → no distributed state needed. Cache evicts
    /// naturally when the date rolls over (new key = no hit). A redeploy
    /// clears it — users get a fresh quiz on first tap after restart, which
    /// is acceptable. This avoids calling Claude repeatedly when the user
    /// taps Quiz multiple times in a session.
    private final Map<String, Map<LocalDate, List<QuizQuestion>>> dailyCache =
            new ConcurrentHashMap<>();

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

        List<WikiPage> allPages = wikiRepository.findByAvatarId(avatarId);
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

        // R3 — bias toward the student's weak spots and under-tested material.
        List<WikiPage> prioritised = pages.stream()
                .sorted(Comparator
                        .comparingDouble(WikiPage::getCertaintyScore)
                        .thenComparingInt(WikiPage::getQuizUseCount))
                .limit(MAX_PAGES_PER_QUIZ)
                .toList();

        log.info("[Pipeline:Quiz] Generating from {} pages: slugs={}",
                prioritised.size(), prioritised.stream().map(WikiPage::getSlug).toList());

        // Record that these pages seeded a quiz so coverage stays balanced.
        wikiRepository.recordQuizUsage(avatarId,
                prioritised.stream().map(WikiPage::getSlug).toList());

        List<QuizQuestion> questions = quizGeneratorPort.generate(avatarId, prioritised);
        log.info("[Pipeline:Quiz] Generated {} questions for avatarId={}", questions.size(), avatarId);

        // Persist the SERVER answer key so the submit path can grade
        // authoritatively (grade integrity) instead of trusting the client's
        // correctMap. Best-effort: a key-write failure must not block the kid's
        // quiz — grading degrades to the client map (logged) for those rows.
        if (!questions.isEmpty()) {
            try {
                answerKeyRepository.saveKeys(avatarId, questions);
            } catch (Exception e) {
                log.warn("[Pipeline:Quiz] Answer-key persistence failed avatarId={}"
                        + " — submit will fall back to client grading: {}",
                        avatarId, e.getMessage());
            }
        }

        // Store in daily cache so repeat taps are instant
        if (!questions.isEmpty()) {
            avatarCache.put(today, questions);
            // Evict yesterday's entry to keep memory bounded (at most 2 dates per avatar)
            avatarCache.entrySet().removeIf(e -> e.getKey().isBefore(today));
        }
        return questions;
    }
}
