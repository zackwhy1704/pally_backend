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
import com.pally.infrastructure.persistence.quiz.QuizAnswerRecordJpaRepository;
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
        QuizResult result = submitQuizAnswersUseCase.execute(submission, correctMap, topicMap);
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
}
