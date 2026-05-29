package com.pally.api.quiz;

import com.pally.api.quiz.dto.FlashcardResponse;
import com.pally.api.quiz.dto.QuizQuestionResponse;
import com.pally.api.quiz.dto.RateFlashcardRequest;
import com.pally.api.quiz.dto.SubmitAnswersRequest;
import com.pally.domain.quiz.AnswerSubmission;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.QuizQuestion;
import com.pally.domain.quiz.QuizResult;
import com.pally.domain.quiz.usecase.GetDailyQuizUseCase;
import com.pally.domain.quiz.usecase.GetFlashcardsUseCase;
import com.pally.domain.quiz.usecase.RateFlashcardUseCase;
import com.pally.domain.quiz.usecase.SubmitQuizAnswersUseCase;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.infrastructure.persistence.quiz.QuizAnswerRecordJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/avatars/{avatarId}")
@RequiredArgsConstructor
public class QuizController {

    private final GetDailyQuizUseCase getDailyQuizUseCase;
    private final SubmitQuizAnswersUseCase submitQuizAnswersUseCase;
    private final GetFlashcardsUseCase getFlashcardsUseCase;
    private final RateFlashcardUseCase rateFlashcardUseCase;
    private final QuizAnswerRecordJpaRepository quizAnswerRecordRepository;
    private final QuizQuestionResultJpaRepository quizQuestionResultRepository;
    private final WikiRepository wikiRepository;

    @GetMapping("/quiz/daily")
    public ResponseEntity<ApiResponse<List<QuizQuestionResponse>>> getDailyQuiz(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        List<QuizQuestion> questions = getDailyQuizUseCase.execute(avatarId, userId);
        List<QuizQuestionResponse> response = questions.stream()
                .map(QuizQuestionResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/quiz/answers")
    public ResponseEntity<ApiResponse<QuizResult>> submitAnswers(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody SubmitAnswersRequest request
    ) {
        AnswerSubmission submission = new AnswerSubmission(avatarId, userId, request.answers());
        Map<String, Integer> correctMap = request.correctMap() != null ? request.correctMap() : Map.of();
        Map<String, String> topicMap = request.topicMap() != null ? request.topicMap() : Map.of();
        Map<String, String> confidenceMap = request.confidenceMap() != null
                ? request.confidenceMap()
                : Map.of();
        QuizResult result = submitQuizAnswersUseCase.execute(
                submission, correctMap, topicMap, confidenceMap);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/flashcards")
    public ResponseEntity<ApiResponse<List<FlashcardResponse>>> getFlashcards(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        List<FlashCard> cards = getFlashcardsUseCase.execute(avatarId, userId);
        List<FlashcardResponse> response = cards.stream()
                .map(FlashcardResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/flashcards/{cardId}/rate")
    public ResponseEntity<ApiResponse<FlashcardResponse>> rateFlashcard(
            @PathVariable String avatarId,
            @PathVariable String cardId,
            @Valid @RequestBody RateFlashcardRequest request
    ) {
        FlashCard updated = rateFlashcardUseCase.execute(cardId, request.rating());
        return ResponseEntity.ok(ApiResponse.success(FlashcardResponse.from(updated)));
    }

    @GetMapping("/quiz/error-patterns")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getErrorPatterns(
            @PathVariable String avatarId
    ) {
        List<Object[]> rows = quizAnswerRecordRepository.findTopErrorTopics(avatarId);
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : rows) {
            result.put((String) row[0], (Long) row[1]);
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /// Daily-quiz journey status. Used by Home + Progress + Quiz screens
    /// to (a) show "Today's quiz complete ✓" instead of re-launching the
    /// same quiz a second time, (b) drive a syllabus-coverage ring
    /// (mastered / total).
    @GetMapping("/quiz/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQuizStatus(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        boolean takenToday = Boolean.TRUE.equals(
                quizQuestionResultRepository.takenToday(userId, avatarId));

        // Coverage = ACTIVE pages on this avatar vs how many have been
        // answered correctly at all (any time). 0.7 mastery threshold so
        // a single lucky answer doesn't count.
        var allMastery = quizQuestionResultRepository
                .findAllTopicMasteryByAvatar(userId, avatarId);
        int mastered = 0;
        for (var r : allMastery) {
            if (((Number) r[1]).doubleValue() >= 0.7) mastered++;
        }
        // Total topics = wiki pages currently in the brain.
        // Re-using the existing helper rather than a new query.
        int totalTopics = wikiRepository
                .findByAvatarId(avatarId).size();

        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "takenToday", takenToday,
                "totalTopics", totalTopics,
                "masteredTopics", mastered
        )));
    }

    /// Per-topic mastery (correct-ratio) for this avatar. Used by the
    /// brain-map screen to colour topic nodes; missing topics = untouched.
    /// R8 — adds reviewRequired so the brain map can pulse pages the quiz
    /// feedback loop has flagged after wrong answers.
    @GetMapping("/topic-mastery")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopicMastery(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        List<Object[]> rows = quizQuestionResultRepository
                .findAllTopicMasteryByAvatar(userId, avatarId);
        java.util.Set<String> reviewSlugs = wikiRepository
                .findReviewRequired(avatarId).stream()
                .map(com.pally.domain.knowledge.WikiPage::getSlug)
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> body = rows.stream()
                .map(r -> {
                    String slug = (String) r[0];
                    return Map.<String, Object>of(
                            "topicSlug", slug,
                            "mastery", ((Number) r[1]).doubleValue(),
                            "attempts", ((Number) r[2]).intValue(),
                            "reviewRequired", reviewSlugs.contains(slug));
                })
                .toList();
        return ResponseEntity.ok(ApiResponse.success(body));
    }
}
