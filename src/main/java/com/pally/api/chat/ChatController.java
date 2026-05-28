package com.pally.api.chat;

import com.pally.api.chat.dto.ChatHistoryResponse;
import com.pally.api.chat.dto.ChatMessageResponse;
import com.pally.api.chat.dto.ChatRequest;
import com.pally.api.chat.dto.ChatSyncRequest;
import com.pally.api.chat.dto.FeedbackRequest;
import com.pally.api.chat.dto.PhotoQuestionRequest;
import com.pally.api.chat.dto.PhotoQuestionResponse;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.usecase.ChatFeedbackService;
import com.pally.domain.chat.usecase.ChatHistoryService;
import com.pally.domain.chat.usecase.ChatSyncService;
import com.pally.domain.chat.usecase.SendMessageUseCase;
import com.pally.domain.chat.usecase.SolvePhotoQuestionsUseCase;
import com.pally.infrastructure.ai.CacheKeepAliveService;
import com.pally.shared.exception.AvatarNotFoundException;
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
 * <p>Streaming chat uses Server-Sent Events (SSE) over WebFlux.
 * Chat history is served as a standard JSON response.
 */
@RestController
@RequestMapping("/api/v1/avatars/{avatarId}")
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private static final int HISTORY_PAGE_SIZE = 50;

    private final SendMessageUseCase sendMessageUseCase;
    private final SolvePhotoQuestionsUseCase solvePhotoQuestionsUseCase;
    private final ChatRepository chatRepository;
    private final ChatMapper chatMapper;
    private final ChatSyncService chatSyncService;
    private final ChatHistoryService chatHistoryService;
    private final ChatFeedbackService chatFeedbackService;
    private final CacheKeepAliveService cacheKeepAliveService;
    private final AvatarRepository avatarRepository;

    /**
     * Streams a chat response from the avatar via Server-Sent Events.
     *
     * <p>Each SSE event carries one of:
     * <ul>
     *   <li>{@code delta} — a text token from the AI model</li>
     *   <li>{@code done}  — signals stream completion</li>
     *   <li>{@code error} — signals a streaming error</li>
     * </ul>
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @param request  chat request containing the user's message
     * @return SSE stream of chat events
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void chat(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody ChatRequest request,
            HttpServletResponse response
    ) throws java.io.IOException {
        response.setContentType("text/event-stream");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");

        java.io.PrintWriter writer = response.getWriter();

        try {
            sendMessageUseCase.executeStream(avatarId, userId, request.message())
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
     *
     * @param userId   user identifier from {@code X-User-Id} header
     * @param avatarId avatar identifier
     * @return list of chat messages
     */
    @GetMapping("/chat/history")
    public List<ChatMessageResponse> getChatHistory(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId
    ) {
        List<ChatMessage> messages = chatRepository.findByAvatarId(avatarId, HISTORY_PAGE_SIZE);
        return chatMapper.toResponseList(messages);
    }

    @PostMapping("/photo-question")
    public PhotoQuestionResponse solvePhotoQuestion(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody PhotoQuestionRequest request
    ) {
        return solvePhotoQuestionsUseCase.execute(avatarId, userId, request.questions());
    }

    @PostMapping("/chat/sync")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Integer> syncMessages(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestBody ChatSyncRequest request
    ) {
        int upserted = chatSyncService.sync(avatarId, userId, request.messages());
        return Map.of("upserted", upserted);
    }

    @GetMapping("/chat/history/full")
    public ChatHistoryResponse getFullHistory(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return new ChatHistoryResponse(chatHistoryService.getHistory(avatarId, limit));
    }

    @PostMapping("/chat/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitFeedback(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId,
            @PathVariable String messageId,
            @RequestBody FeedbackRequest request
    ) {
        chatFeedbackService.submitFeedback(messageId, request.feedbackType());
    }

    @PostMapping("/chat/session-start")
    @ResponseStatus(HttpStatus.OK)
    public void sessionStart(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        cacheKeepAliveService.startKeepalive(avatarId);
    }

    @PostMapping("/chat/session-end")
    @ResponseStatus(HttpStatus.OK)
    public void sessionEnd(
            @AuthenticationPrincipal String userId,
            @PathVariable String avatarId) {
        cacheKeepAliveService.stopKeepalive(avatarId);
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

        var avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));
        avatar.setTeachingMode(mode);
        avatarRepository.save(avatar);

        return Map.of("mode", mode.name());
    }
}
