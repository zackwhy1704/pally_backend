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

    private final GetDailyQuizUseCase getDailyQuizUseCase;
    private final SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    private final GetFlashcardsUseCase getFlashcardsUseCase;
    private final RateFlashcardUseCase rateFlashcardUseCase;
    private final QuizAnswerRecordJpaRepository quizAnswerRecordRepository;
    private final QuizQuestionResultJpaRepository quizQuestionResultRepository;
    private final AvatarJpaRepository avatarRepository;
    private final WikiRepository wikiRepository;
    private final FlashcardRepository flashcardRepository;
    private final ClaudeFlashcardGenerator flashcardGenerator;

    public List<QuizQuestionResponse> getDailyQuiz(String userId, String avatarId) {
        List<QuizQuestion> questions = getDailyQuizUseCase.execute(avatarId, userId);
        return questions.stream().map(QuizQuestionResponse::from).toList();
    }

    public QuizResult submitAnswers(String userId, String avatarId, SubmitAnswersRequest request) {
        AnswerSubmission submission = new AnswerSubmission(avatarId, userId, request.answers());
        Map<String, Integer> correctMap = request.correctMap() != null ? request.correctMap() : Map.of();
        Map<String, String> topicMap = request.topicMap() != null ? request.topicMap() : Map.of();
        Map<String, String> confidenceMap = request.confidenceMap() != null
                ? request.confidenceMap() : Map.of();
        int durationSeconds = DurationClamp.clamp(request.durationSeconds());
        return submitQuizAnswersUseCase.execute(
                submission, correctMap, topicMap, confidenceMap, durationSeconds);
    }

    public List<FlashcardResponse> getFlashcards(String userId, String avatarId) {
        List<FlashCard> cards = getFlashcardsUseCase.execute(avatarId, userId);
        return cards.stream().map(FlashcardResponse::from).toList();
    }

    /// On-demand (re)generate flashcards from the avatar's compiled wiki pages.
    /// Returns the count generated and whether wiki pages exist. Idempotent:
    /// existing cards for each slug are deleted before regenerating.
    public Map<String, Object> generateFlashcards(String userId, String avatarId) {
        List<WikiPage> pages = wikiRepository.findByAvatarId(avatarId);
        boolean hasWikiPages = !pages.isEmpty();
        int totalGenerated = 0;

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

        return Map.of("generated", totalGenerated, "hasWikiPages", hasWikiPages);
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
