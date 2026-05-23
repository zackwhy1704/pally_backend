package com.pally.api.chat;

import com.pally.api.chat.dto.ChatMessageResponse;
import com.pally.api.chat.dto.ChatRequest;
import com.pally.api.chat.dto.PhotoQuestionRequest;
import com.pally.api.chat.dto.PhotoQuestionResponse;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.usecase.SendMessageUseCase;
import com.pally.domain.chat.usecase.SolvePhotoQuestionsUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

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
}
