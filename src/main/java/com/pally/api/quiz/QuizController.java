package com.pally.api.quiz;

import com.pally.api.quiz.dto.FlashcardResponse;
import com.pally.api.quiz.dto.QuizQuestionResponse;
import com.pally.api.quiz.dto.RateFlashcardRequest;
import com.pally.api.quiz.dto.SubmitAnswersRequest;
import com.pally.domain.quiz.QuizResult;
import com.pally.domain.quiz.QuizService;
import com.pally.shared.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Quiz + flashcard endpoints, scoped under {@code /api/v1/avatars/{avatarId}}.
 * Thin HTTP layer: delegate to {@link QuizService} → wrap in {@link ApiResponse}.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}")
@RequiredArgsConstructor
public class QuizController {

    private final QuizService quizService;

    @GetMapping("/quiz/daily")
    public ResponseEntity<ApiResponse<List<QuizQuestionResponse>>> getDailyQuiz(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(ApiResponse.success(quizService.getDailyQuiz(userId, avatarId)));
    }

    @PostMapping("/quiz/answers")
    public ResponseEntity<ApiResponse<QuizResult>> submitAnswers(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody SubmitAnswersRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(quizService.submitAnswers(userId, avatarId, request)));
    }

    @GetMapping("/flashcards")
    public ResponseEntity<ApiResponse<List<FlashcardResponse>>> getFlashcards(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(ApiResponse.success(quizService.getFlashcards(userId, avatarId)));
    }

    @PostMapping("/flashcards/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateFlashcards(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(
                ApiResponse.success(quizService.generateFlashcards(userId, avatarId)));
    }

    @PostMapping("/flashcards/{cardId}/rate")
    public ResponseEntity<ApiResponse<FlashcardResponse>> rateFlashcard(
            @PathVariable String avatarId,
            @PathVariable String cardId,
            @Valid @RequestBody RateFlashcardRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success(quizService.rateFlashcard(avatarId, cardId, request)));
    }

    @GetMapping("/quiz/error-patterns")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getErrorPatterns(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(
                ApiResponse.success(quizService.getErrorPatterns(userId, avatarId)));
    }

    @GetMapping("/quiz/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQuizStatus(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(
                ApiResponse.success(quizService.getQuizStatus(userId, avatarId)));
    }

    @GetMapping("/topic-mastery")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getTopicMastery(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return ResponseEntity.ok(
                ApiResponse.success(quizService.getTopicMastery(userId, avatarId)));
    }
}
