package com.pally.api.chat;

import com.pally.api.chat.dto.ChatRequest;
import com.pally.api.chat.dto.ChatSyncRequest;
import com.pally.api.chat.dto.FeedbackRequest;
import com.pally.api.chat.dto.PhotoQuestionRequest;
import com.pally.domain.chat.dto.ChatHistoryResponse;
import com.pally.domain.chat.dto.ChatMessageResponse;
import com.pally.domain.chat.dto.PhotoQuestionResponse;
import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.chat.ChatOrchestrationService;
import com.pally.domain.chat.usecase.SendMessageUseCase;
import com.pally.domain.chat.usecase.SolvePhotoQuestionsUseCase;
import com.pally.infrastructure.ratelimit.ChatRateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * REST controller for chat endpoints.
 *
 * <p>This controller is intentionally thin: each handler validates input /
 * extracts the principal, delegates to one service, and returns the result.
 * All business logic, repository calls, and post-session side-effects live
 * in {@link ChatOrchestrationService} or the streaming use cases.
 *
 * <p>Streaming chat uses Server-Sent Events (SSE). All other endpoints use
 * standard JSON.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final ChatOrchestrationService chatOrchestrationService;
    private final SendMessageUseCase sendMessageUseCase;
    private final SolvePhotoQuestionsUseCase solvePhotoQuestionsUseCase;
    private final com.pally.domain.consent.ConsentGuard consentGuard;
    private final ChatRateLimiter chatRateLimiter;

    /**
     * Streams a chat response from the avatar via Server-Sent Events.
     *
     * <p>Each SSE event carries one of:
     * <ul>
     *   <li>{@code delta} — a text token from the AI model</li>
     *   <li>{@code done}  — signals stream completion</li>
     *   <li>{@code error} — signals a streaming error</li>
     * </ul>
     */
    @PostMapping("/chat")
    public void chat(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody ChatRequest request,
            HttpServletResponse response
    ) throws java.io.IOException {
        chatRateLimiter.check(userId);
        // Gate BEFORE the SSE stream opens. Once we flush HTTP 200 + ": connected"
        // below, we can no longer return a clean 403, so an un-consented or
        // under-13-unlinked user would only get a generic SSE error the mobile
        // interceptor can't act on. Checking here lets GlobalExceptionHandler
        // return a proper 403 (AI_CONSENT_REQUIRED / PARENT_LINK_REQUIRED) that
        // the client maps to the disclosure / link-a-grown-up screen.
        consentGuard.requireAiConsent(userId);
        consentGuard.requireGuardianIfUnder13(userId);
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        java.io.PrintWriter writer = response.getWriter();

        // Flush HTTP 200 + headers IMMEDIATELY so the client knows it is
        // connected before any synchronous pre-processing (moderation,
        // context assembly, DB queries) begins.  Without this flush, the
        // client receives no bytes until ~118s later when the moderation
        // Claude call finally times out, causing the Flutter receiveTimeout
        // to fire and producing a 0-char stream instead of a real error.
        writer.write(": connected\n\n");
        writer.flush();

        try {
            sendMessageUseCase.executeStream(avatarId, userId, request.message(), request.moduleId())
                    .toIterable()
                    .forEach(event -> {
                        writer.write("event: " + event.type() + "\n");
                        String payload = event.payload();
                        if (payload.contains("\n")) {
                            for (String line : payload.split("\n", -1)) {
                                writer.write("data: " + line + "\n");
                            }
                        } else {
                            writer.write("data: " + payload + "\n");
                        }
                        writer.write("\n");
                        writer.flush();
                    });
        } catch (Exception e) {
            log.error("[Chat] SSE stream failed for avatar={}: {}", avatarId, e.getMessage(), e);
            writer.write("event: error\n");
            writer.write("data: Sorry, something went wrong. Please try again.\n");
            writer.write("\n");
            writer.flush();
        }
    }

    /**
     * Returns the chat history for an avatar (most recent messages first).
     */
    @GetMapping("/chat/history")
    public List<ChatMessageResponse> getChatHistory(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        return chatOrchestrationService.getChatHistory(userId, avatarId);
    }

    /**
     * Solves photo homework questions. Accepts a JSON body with {@code questions} array.
     */
    @PostMapping("/photo-question")
    public PhotoQuestionResponse solvePhotoQuestion(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody PhotoQuestionRequest request
    ) {
        chatRateLimiter.check(userId);
        return solvePhotoQuestionsUseCase.execute(avatarId, userId, request.questions());
    }

    /**
     * Multipart variant: sends the original image to the vision model alongside
     * OCR-extracted questions, dramatically improving STEM/math accuracy.
     */
    @PostMapping(value = "/photo-question-vision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PhotoQuestionResponse solvePhotoQuestionWithImage(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @org.springframework.web.bind.annotation.RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            @org.springframework.web.bind.annotation.RequestPart("questions") String questionsJson
    ) {
        chatRateLimiter.check(userId);
        List<String> questions;
        try {
            questions = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(questionsJson, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            throw new com.pally.shared.exception.BusinessException(
                    "Invalid questions format — expected a JSON array of strings", 400);
        }
        if (questions.isEmpty()) {
            throw new com.pally.shared.exception.BusinessException(
                    "questions must not be empty", 400);
        }
        try {
            byte[] imageBytes = file.getBytes();
            String mimeType = file.getContentType();
            return solvePhotoQuestionsUseCase.execute(avatarId, userId, questions, imageBytes, mimeType);
        } catch (java.io.IOException e) {
            throw new com.pally.shared.exception.BusinessException(
                    "Could not read the uploaded image", 400);
        }
    }

    @PostMapping("/chat/sync")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Integer> syncMessages(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody ChatSyncRequest request
    ) {
        return chatOrchestrationService.syncMessages(userId, avatarId, request.messages());
    }

    @GetMapping("/chat/history/full")
    public ChatHistoryResponse getFullHistory(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return chatOrchestrationService.getFullHistory(avatarId, limit);
    }

    @PostMapping("/chat/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitFeedback(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String messageId,
            @RequestBody FeedbackRequest request
    ) {
        chatOrchestrationService.submitFeedback(messageId, request.feedbackType());
    }

    @PostMapping("/chat/session-start")
    @ResponseStatus(HttpStatus.OK)
    public void sessionStart(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        chatOrchestrationService.sessionStart(avatarId);
    }

    /**
     * Credits +5 XP for ending a chat session — capped at once per avatar per SGT day.
     * Returns the level-up signal so the client can celebrate on the first legitimate credit.
     */
    @PostMapping("/chat/session-end")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Object> sessionEnd(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        return chatOrchestrationService.sessionEnd(userId, avatarId);
    }

    @PatchMapping("/teaching-mode")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> setTeachingMode(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody Map<String, String> body
    ) {
        String modeStr = body.getOrDefault("mode", "TEACHING").toUpperCase();
        TeachingMode mode;
        try {
            mode = TeachingMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            mode = TeachingMode.TEACHING;
        }
        return chatOrchestrationService.setTeachingMode(userId, avatarId, mode);
    }
}
