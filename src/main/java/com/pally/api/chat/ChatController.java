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
import com.pally.shared.exception.AiConsentRequiredException;
import com.pally.shared.exception.GuardianRequiredException;
import com.pally.shared.exception.ParentalConsentPendingException;
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
    private final com.pally.domain.chat.port.ChatSessionCachePort chatSessionCachePort;

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
        // Gate BEFORE setting the SSE content type. Once response.setContentType
        // is called with text/event-stream, Spring's content negotiation rejects
        // any attempt to return JSON (Accept: text/event-stream is still set on
        // the request), causing HttpMediaTypeNotAcceptableException instead of
        // the intended 403. Writing JSON directly to HttpServletResponse bypasses
        // Spring entirely and delivers a parseable error body to the client.
        try {
            // Child-data ingress guard FIRST (a pending under-13's text must not stream
            // to the model), then the AI-transfer gate. Both written as JSON before the
            // SSE content-type is set.
            consentGuard.requireChildDataIngressConsent(userId);
            consentGuard.requireAiConsent(userId);
        } catch (ParentalConsentPendingException e) {
            writeParentalConsentPendingError(response, e);
            return;
        } catch (AiConsentRequiredException e) {
            writeJsonError(response, 403, "AI_CONSENT_REQUIRED", e.getReason(), e.getMessage());
            return;
        } catch (GuardianRequiredException e) {
            // Unknown age (AGE_DECLARATION_REQUIRED) etc.
            writeJsonError(response, 403, "PARENT_LINK_REQUIRED", e.getReason(), e.getMessage());
            return;
        }

        // A real, permitted chat turn — reset the keepalive idle timer so an active
        // session stays warm and an abandoned one self-terminates once turns stop.
        // Touch-only (never starts a loop), so it cannot leak.
        chatSessionCachePort.recordActivity(avatarId);

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
        return chatOrchestrationService.getFullHistory(userId, avatarId, limit);
    }

    @PostMapping("/chat/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitFeedback(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String messageId,
            @RequestBody FeedbackRequest request
    ) {
        chatOrchestrationService.submitFeedback(messageId, request.feedbackType(), userId);
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

    /**
     * Writes a JSON error body directly to the raw response, bypassing Spring's
     * content negotiation. Required for the SSE endpoint: the request carries
     * {@code Accept: text/event-stream}, so Spring refuses to serialize JSON
     * through the normal handler return path.
     */
    /// Rich PARENTAL_CONSENT_PENDING error for the SSE path — same envelope shape as the
    /// global handler so the central client handler renders the consent-pending + resend
    /// panel identically whether the block came from chat or a JSON endpoint.
    private void writeParentalConsentPendingError(HttpServletResponse response,
            ParentalConsentPendingException e) throws java.io.IOException {
        response.setStatus(403);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String masked = e.getMaskedParentEmail() == null ? "" : e.getMaskedParentEmail();
        String body = String.format(
            "{\"status\":403,\"message\":\"%s\",\"data\":{\"code\":\"%s\",\"reason\":\"%s\","
            + "\"parentEmailMasked\":\"%s\",\"resendAvailable\":%b,\"resendAvailableInSeconds\":%d}}",
            "Your account is waiting for your parent to approve it.",
            ParentalConsentPendingException.CODE, ParentalConsentPendingException.CODE,
            masked.replace("\"", "\\\""), e.isResendAvailable(), e.getResendAvailableInSeconds());
        response.getWriter().write(body);
        response.getWriter().flush();
    }

    private void writeJsonError(HttpServletResponse response, int status,
            String code, String reason, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        String body = String.format(
            "{\"status\":%d,\"message\":\"%s\",\"data\":{\"code\":\"%s\",\"reason\":\"%s\"}}",
            status,
            message.replace("\"", "\\\""),
            code,
            reason == null ? "" : reason.replace("\"", "\\\"")
        );
        response.getWriter().write(body);
        response.getWriter().flush();
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
