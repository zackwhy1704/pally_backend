package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.AssembledContext;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.domain.chat.port.ChatPort;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Use case: send a message to an avatar and receive a streaming response.
 *
 * <p>Uses ClaudeContextAssembler to build a tiered, topic-gated system prompt
 * instead of dumping all wiki pages into every request.</p>
 */
@Service
@RequiredArgsConstructor
public class SendMessageUseCase {

    private static final Logger log = LoggerFactory.getLogger(SendMessageUseCase.class);
    private static final int HISTORY_LIMIT = 20;

    private final AvatarRepository avatarRepository;
    private final ChatRepository chatRepository;
    private final ChatPort chatProxy;
    private final ClaudeContextAssembler contextAssembler;

    public record StreamEvent(String type, String payload) {}

    public Flux<StreamEvent> executeStream(String avatarId, String userId, String userMessage) {
        Avatar avatar = avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        // Persist user message before streaming
        ChatMessage userMsg = ChatMessage.create(avatarId, userId, ChatMessage.Role.USER, userMessage, null);
        chatRepository.save(userMsg);

        // Assemble tiered context — replaces flat wiki dump
        AssembledContext context = contextAssembler.assemble(avatar, userMessage);

        // Fetch recent history for conversational continuity
        List<ChatMessage> history = chatRepository.findByAvatarId(avatarId, HISTORY_LIMIT);

        log.debug("[Chat] Streaming avatarId={} historySize={} tier1-4 assembled",
                avatarId, history.size());

        StringBuilder replyBuffer = new StringBuilder();

        return chatProxy.streamChat(context.systemPrompt(), history, userMessage)
                .doOnNext(event -> {
                    if (event instanceof ChatStreamEvent.Token token) {
                        replyBuffer.append(token.text());
                    }
                })
                .doOnComplete(() -> {
                    if (!replyBuffer.isEmpty()) {
                        ChatMessage assistantMsg = ChatMessage.createWithTrace(
                                avatarId, userId, ChatMessage.Role.ASSISTANT,
                                replyBuffer.toString(), null,
                                context.harnessTrace()
                        );
                        chatRepository.save(assistantMsg);
                        log.debug("[Chat] Saved reply avatarId={} chars={}", avatarId, replyBuffer.length());
                    }
                })
                .map(event -> switch (event) {
                    case ChatStreamEvent.Token t -> new StreamEvent("delta", t.text());
                    case ChatStreamEvent.Done d  -> new StreamEvent("done", d.sourceFile() != null ? d.sourceFile() : "");
                    case ChatStreamEvent.Error e -> new StreamEvent("error", e.message());
                });
    }
}
