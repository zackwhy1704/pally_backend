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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

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
    public Flux<ServerSentEvent<String>> chat(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody ChatRequest request
    ) {
        return sendMessageUseCase.executeStream(avatarId, userId, request.message())
                .map(event -> ServerSentEvent.<String>builder()
                        .event(event.type())
                        .data(event.payload())
                        .build());
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
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId
    ) {
        List<ChatMessage> messages = chatRepository.findByAvatarId(avatarId, HISTORY_PAGE_SIZE);
        return chatMapper.toResponseList(messages);
    }

    @PostMapping("/photo-question")
    public PhotoQuestionResponse solvePhotoQuestion(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @Valid @RequestBody PhotoQuestionRequest request
    ) {
        return solvePhotoQuestionsUseCase.execute(avatarId, userId, request.questions());
    }

    @PostMapping("/chat/sync")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, Integer> syncMessages(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @RequestBody ChatSyncRequest request
    ) {
        int upserted = chatSyncService.sync(avatarId, userId, request.messages());
        return Map.of("upserted", upserted);
    }

    @GetMapping("/chat/history/full")
    public ChatHistoryResponse getFullHistory(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @RequestParam(defaultValue = "50") int limit
    ) {
        return new ChatHistoryResponse(chatHistoryService.getHistory(avatarId, limit));
    }

    @PostMapping("/chat/{messageId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitFeedback(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId,
            @PathVariable String messageId,
            @RequestBody FeedbackRequest request
    ) {
        chatFeedbackService.submitFeedback(messageId, request.feedbackType());
    }

    @PostMapping("/chat/session-start")
    @ResponseStatus(HttpStatus.OK)
    public void sessionStart(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId) {
        cacheKeepAliveService.startKeepalive(avatarId);
    }

    @PostMapping("/chat/session-end")
    @ResponseStatus(HttpStatus.OK)
    public void sessionEnd(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable String avatarId) {
        cacheKeepAliveService.stopKeepalive(avatarId);
    }

    @PatchMapping("/teaching-mode")
    @ResponseStatus(HttpStatus.OK)
    public Map<String, String> setTeachingMode(
            @RequestHeader("X-User-Id") String userId,
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
