package com.pally.domain.quiz;

import com.pally.domain.quiz.dto.FlashcardResponse;
import com.pally.domain.quiz.dto.QuizQuestionResponse;
import com.pally.domain.quiz.dto.RateFlashcardRequest;
import com.pally.domain.quiz.dto.SubmitAnswersRequest;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.quiz.AnswerSubmission;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.domain.quiz.QuizAnswerKeyRepository;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.QuizResult;
import com.pally.domain.quiz.usecase.GetDailyQuizUseCase;
import com.pally.domain.quiz.usecase.GetFlashcardsUseCase;
import com.pally.domain.quiz.usecase.RateFlashcardUseCase;
import com.pally.domain.quiz.usecase.SubmitQuizAnswersUseCase;
import com.pally.infrastructure.ai.ClaudeFlashcardGenerator;
import com.pally.infrastructure.persistence.avatar.AvatarJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizAnswerRecordJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.util.DurationClamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application service for quizzes + flashcards — owns all logic + repo access so
 * {@link QuizController} stays a thin HTTP delegator. Returns the response DTOs
 * the controller wraps, so the HTTP contract is identical.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuizService {

    /// Above this many wiki pages, flashcard auto-generate-on-open is replaced by
    /// an explicit CTA (avoids the synchronous all-pages hang). Tunable.
    @org.springframework.beans.factory.annotation.Value("${flashcard.auto-generate-max-pages:15}")
    private int autoGenMaxPages;

    private final GetDailyQuizUseCase getDailyQuizUseCase;
    private final SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    private final QuizIdempotencyRepository quizIdempotencyRepository;
    private final GetFlashcardsUseCase getFlashcardsUseCase;
    private final RateFlashcardUseCase rateFlashcardUseCase;
    private final QuizAnswerRecordJpaRepository quizAnswerRecordRepository;
    private final QuizQuestionResultJpaRepository quizQuestionResultRepository;
    private final AvatarJpaRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final FlashcardRepository flashcardRepository;
    private final ClaudeFlashcardGenerator flashcardGenerator;
    private final QuizAnswerKeyRepository answerKeyRepository;

    public List<QuizQuestionResponse> getDailyQuiz(String userId, String avatarId) {
        List<QuizQuestion> questions = getDailyQuizUseCase.execute(avatarId, userId);
        return serveGradable(avatarId, questions);
    }

    /// THE serving chokepoint for gradable quiz questions. Every served quiz
    /// flows through here, which guarantees two things in ONE place so a future
    /// quiz type cannot ship without them (enforced by an enumeration test that
    /// {@code QuizQuestionResponse.from} is called nowhere else):
    ///   1. The SERVER answer key is persisted — so submit grades from it and
    ///      ignores the client map (closes answer *tampering*).
    ///   2. {@code correctIndex} is WITHHELD for teacher-graded (centre) quizzes
    ///      — so a student can't read the key out of the response and replay it
    ///      into teacher-visible mastery (closes answer *exposure*). Feedback is
    ///      returned post-submit in {@link QuizResult#feedback()} instead.
    ///
    /// If key persistence fails for a centre quiz we keep exposing the key this
    /// once (logged) so the kid can still submit — availability over secrecy on
    /// a rare transient write failure, never a silently un-gradable quiz.
    private List<QuizQuestionResponse> serveGradable(
            String avatarId, List<QuizQuestion> questions) {
        if (questions.isEmpty()) return List.of();

        boolean keyPersisted = false;
        try {
            answerKeyRepository.saveKeys(avatarId, questions);
            keyPersisted = true;
        } catch (Exception e) {
            log.warn("[Quiz] Answer-key persistence failed avatar={}: {}",
                    avatarId, e.getMessage());
        }

        boolean teacherGraded = avatarRepository.existsByIdAndCentreAvatarTrue(avatarId);
        boolean exposeKey = !teacherGraded || !keyPersisted;
        if (teacherGraded && !keyPersisted) {
            log.warn("[Quiz] Exposing answer key for centre avatar {} this serve — "
                    + "key not persisted, falling back to client grading (degraded)",
                    avatarId);
        }
        return questions.stream()
                .map(q -> QuizQuestionResponse.from(q, exposeKey))
                .toList();
    }

    public QuizResult submitAnswers(String userId, String avatarId, SubmitAnswersRequest request) {
        AnswerSubmission submission = new AnswerSubmission(avatarId, userId, request.answers());
        Map<String, Integer> correctMap = request.correctMap() != null ? request.correctMap() : Map.of();
        Map<String, String> topicMap = request.topicMap() != null ? request.topicMap() : Map.of();
        Map<String, String> confidenceMap = request.confidenceMap() != null
                ? request.confidenceMap() : Map.of();
        int durationSeconds = DurationClamp.clamp(request.durationSeconds());

        // No key (legacy client) → grade normally. With a key → dedup: a replay
        // returns the first result instead of re-crediting XP/stars.
        String idempotencyKey = request.idempotencyKey();
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return submitQuizAnswersUseCase.execute(
                    submission, correctMap, topicMap, confidenceMap, durationSeconds);
        }

        // Fast path: the attempt was already graded (retry after completion).
        var already = quizIdempotencyRepository.findResult(userId, idempotencyKey);
        if (already.isPresent()) {
            return already.get();
        }
        try {
            return submitQuizAnswersUseCase.executeWithIdempotency(
                    submission, correctMap, topicMap, confidenceMap, durationSeconds, idempotencyKey);
        } catch (DuplicateSubmissionException e) {
            // Lost the claim race → the winner has committed its result by now.
            return quizIdempotencyRepository.findResult(userId, idempotencyKey)
                    .orElseThrow(() -> e); // extremely rare: winner rolled back → surface
        }
    }

    public List<FlashcardResponse> getFlashcards(String userId, String avatarId) {
        List<FlashCard> cards = getFlashcardsUseCase.execute(avatarId, userId);
        return cards.stream().map(FlashcardResponse::from).toList();
    }

    /// On-demand (re)generate flashcards from the avatar's compiled wiki pages.
    /// Returns the count generated and whether wiki pages exist. Idempotent:
    /// existing cards for each slug are deleted before regenerating.
    /// {@code confirmed=false} is an auto-backfill (screen open). For a large
    /// corpus we do NOT run the synchronous all-pages loop unprompted — it hangs
    /// the screen — and instead return the scope so the client shows an explicit
    /// "Generate cards (~N pages)" CTA. A confirmed call (the CTA) proceeds.
    /// (Interim shield until flashcard generation goes async — see DEFERRED.md.)
    public Map<String, Object> generateFlashcards(String userId, String avatarId, boolean confirmed) {
        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        boolean hasWikiPages = !pages.isEmpty();
        int totalGenerated = 0;

        if (hasWikiPages && !confirmed && pages.size() > autoGenMaxPages) {
            log.info("[Flashcard] Auto-gen deferred to CTA user={} avatar={} pages={} (> cap {})",
                    userId, avatarId, pages.size(), autoGenMaxPages);
            return Map.of("generated", 0, "hasWikiPages", true,
                    "needsConfirmation", true, "pageCount", pages.size());
        }

        if (hasWikiPages) {
            log.info("[Flashcard] Manual generate user={} avatar={} pages={}",
                    userId, avatarId, pages.size());
            for (WikiPage page : pages) {
                try {
                    flashcardGenerator.generateAndSaveForPage(avatarId, page);
                    totalGenerated += flashcardRepository
                            .countByAvatarIdAndSourceSlug(avatarId, page.getSlug());
                } catch (Exception e) {
                    log.warn("[Flashcard] Generate failed slug={}: {}",
                            page.getSlug(), e.getMessage());
                }
            }
            log.info("[Flashcard] Generate complete avatar={} total={}", avatarId, totalGenerated);
        }

        return Map.of("generated", totalGenerated, "hasWikiPages", hasWikiPages,
                "needsConfirmation", false, "pageCount", pages.size());
    }

    public FlashcardResponse rateFlashcard(
            String userId, String avatarId, String cardId, RateFlashcardRequest request) {
        FlashCard updated = rateFlashcardUseCase.execute(cardId, request.rating(), userId);
        return FlashcardResponse.from(updated);
    }

    public Map<String, Long> getErrorPatterns(String userId, String avatarId) {
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        List<Object[]> rows = quizAnswerRecordRepository.findTopErrorTopics(avatarId);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], (Long) row[1]);
        }
        return result;
    }

    /// Daily-quiz journey status — "taken today" + syllabus-coverage ring
    /// (mastered / total) using a 0.7 mastery threshold.
    public Map<String, Object> getQuizStatus(String userId, String avatarId) {
        boolean takenToday = Boolean.TRUE.equals(
                quizQuestionResultRepository.takenToday(userId, avatarId));

        var allMastery = quizQuestionResultRepository.findAllTopicMasteryByAvatar(userId, avatarId);
        int mastered = 0;
        for (var r : allMastery) {
            if (((Number) r[1]).doubleValue() >= 0.7) mastered++;
        }
        int totalTopics = wikiRepository.countActiveByAvatarId(avatarId);

        return Map.of(
                "takenToday", takenToday,
                "totalTopics", totalTopics,
                "masteredTopics", mastered);
    }

    /// Per-topic mastery for this avatar (brain-map node colouring). Adds
    /// reviewRequired so the map can pulse pages flagged after wrong answers.
    public List<Map<String, Object>> getTopicMastery(String userId, String avatarId) {
        List<Object[]> rows = quizQuestionResultRepository
                .findAllTopicMasteryByAvatar(userId, avatarId);
        java.util.Set<String> reviewSlugs = wikiRepository
                .findReviewRequired(avatarId).stream()
                .map(WikiPage::getSlug)
                .collect(java.util.stream.Collectors.toSet());
        return rows.stream()
                .map(r -> {
                    String slug = (String) r[0];
                    return Map.<String, Object>of(
                            "topicSlug", slug,
                            "mastery", ((Number) r[1]).doubleValue(),
                            "attempts", ((Number) r[2]).intValue(),
                            "reviewRequired", reviewSlugs.contains(slug));
                })
                .toList();
    }
}
