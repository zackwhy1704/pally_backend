package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.api.chat.dto.QuestionAnswerDto;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.infrastructure.observability.ClaudeMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests the multimodal (vision) photo-question path in ClaudePhotoQuestionSolver.
 * Verifies that:
 * 1. When imageBytes are provided, completeVisionWithTools is called
 * 2. When imageBytes are null, completeWithTools (text-only) is called
 * 3. When vision call fails, falls back to text-only path
 */
@ExtendWith(MockitoExtension.class)
class ClaudePhotoQuestionSolverVisionTest {

    @Mock private ClaudeApiClient apiClient;
    @Mock private ModelRouter modelRouter;
    @Mock private CalculatorTool calculatorTool;
    @Mock private ClaudeMetrics metrics;

    private ClaudePhotoQuestionSolver solver;
    private Avatar testAvatar;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        solver = new ClaudePhotoQuestionSolver(apiClient, objectMapper, modelRouter, calculatorTool, metrics);
        testAvatar = Avatar.create("user-1", "MathBot", Subject.MATHS, CharacterType.MOCHI);

        // Default model routing
        lenient().when(modelRouter.forPhotoQuestion()).thenReturn("claude-haiku-4.5");
        lenient().when(modelRouter.forPhotoQuestionHeavy()).thenReturn("claude-haiku-4.5");
        lenient().when(modelRouter.forPhotoQuestionMax()).thenReturn("claude-haiku-4.5");
        lenient().when(modelRouter.getHaikuModel()).thenReturn("claude-haiku-4.5");
        lenient().when(calculatorTool.name()).thenReturn("calculator");
    }

    private static final String VALID_RESPONSE = """
            [{"questionIndex":1,"questionText":"2+2=?","answer":"4",
              "steps":["Add 2 and 2"],"explanation":"Great job!",
              "visualType":"NONE","calculatorVerified":true}]
            """;

    private static final String CLASSIFY_RESPONSE = """
            [{"questionIndex":1,"visualType":"NONE","visualConfidence":"HIGH"}]
            """;

    @Test
    void solveQuestions_withImageBytes_usesVisionApi() {
        byte[] imageBytes = new byte[]{1, 2, 3};
        String mimeType = "image/jpeg";

        // Classification call returns NONE
        when(apiClient.complete(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(CLASSIFY_RESPONSE);
        // Vision call returns valid answer
        when(apiClient.completeVisionWithTools(anyString(), anyInt(), anyString(),
                eq(imageBytes), eq(mimeType), anyList(), eq("photo-solver-vision")))
                .thenReturn(VALID_RESPONSE);

        List<QuestionAnswerDto> answers = solver.solveQuestions(
                testAvatar, List.of(), List.of("2+2=?"), imageBytes, mimeType);

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().answer()).isEqualTo("4");

        // Verify vision method was called, NOT text-only
        verify(apiClient).completeVisionWithTools(anyString(), anyInt(), anyString(),
                eq(imageBytes), eq(mimeType), anyList(), eq("photo-solver-vision"));
        verify(apiClient, never()).completeWithTools(anyString(), anyInt(), anyString(),
                anyList(), eq("photo-solver"));
    }

    @Test
    void solveQuestions_withoutImageBytes_usesTextOnlyApi() {
        when(apiClient.complete(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(CLASSIFY_RESPONSE);
        when(apiClient.completeWithTools(anyString(), anyInt(), anyString(),
                anyList(), eq("photo-solver")))
                .thenReturn(VALID_RESPONSE);

        List<QuestionAnswerDto> answers = solver.solveQuestions(
                testAvatar, List.of(), List.of("2+2=?"), null, null);

        assertThat(answers).hasSize(1);

        // Verify text-only method was called
        verify(apiClient).completeWithTools(anyString(), anyInt(), anyString(),
                anyList(), eq("photo-solver"));
        verify(apiClient, never()).completeVisionWithTools(anyString(), anyInt(), anyString(),
                any(byte[].class), anyString(), anyList(), anyString());
    }

    @Test
    void solveQuestions_visionFails_fallsBackToTextOnly() {
        byte[] imageBytes = new byte[]{1, 2, 3};

        when(apiClient.complete(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(CLASSIFY_RESPONSE);
        // Vision call fails
        when(apiClient.completeVisionWithTools(anyString(), anyInt(), anyString(),
                any(byte[].class), anyString(), anyList(), anyString()))
                .thenThrow(new RuntimeException("Vision API error"));
        // Text-only fallback succeeds
        when(apiClient.completeWithTools(anyString(), anyInt(), anyString(),
                anyList(), eq("photo-solver")))
                .thenReturn(VALID_RESPONSE);

        List<QuestionAnswerDto> answers = solver.solveQuestions(
                testAvatar, List.of(), List.of("2+2=?"), imageBytes, "image/jpeg");

        assertThat(answers).hasSize(1);
        assertThat(answers.getFirst().answer()).isEqualTo("4");

        // Verify both were called: vision first, then text fallback
        verify(apiClient).completeVisionWithTools(anyString(), anyInt(), anyString(),
                any(byte[].class), anyString(), anyList(), anyString());
        verify(apiClient).completeWithTools(anyString(), anyInt(), anyString(),
                anyList(), eq("photo-solver"));
    }

    @Test
    void solveQuestions_legacyOverload_delegatesToNullImage() {
        when(apiClient.complete(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn(CLASSIFY_RESPONSE);
        when(apiClient.completeWithTools(anyString(), anyInt(), anyString(),
                anyList(), eq("photo-solver")))
                .thenReturn(VALID_RESPONSE);

        // Call the legacy (no image) overload
        List<QuestionAnswerDto> answers = solver.solveQuestions(
                testAvatar, List.of(), List.of("2+2=?"));

        assertThat(answers).hasSize(1);
        verify(apiClient, never()).completeVisionWithTools(anyString(), anyInt(), anyString(),
                any(byte[].class), anyString(), anyList(), anyString());
    }
}
