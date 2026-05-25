package com.pally.domain.chat.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.AssembledContext;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.domain.chat.port.ChatPort;
import com.pally.infrastructure.ai.CacheMetrics;
import com.pally.infrastructure.ai.ClaudeContextAssembler;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Use case: send a message to an avatar and receive a streaming response.
 *
 * <p>Uses {@link ClaudeContextAssembler} to build structured cache blocks.
 * Cache metrics are saved asynchronously after the stream completes.
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

        ChatMessage userMsg = ChatMessage.create(avatarId, userId, ChatMessage.Role.USER, userMessage, null);
        chatRepository.save(userMsg);

        AssembledContext context = contextAssembler.assemble(avatar, userMessage);
        List<ChatMessage> history = chatRepository.findByAvatarId(avatarId, HISTORY_LIMIT);

        log.debug("[Chat] Streaming avatarId={} historySize={} blocks={}",
                avatarId, history.size(), context.systemBlocks().size());

        StringBuilder replyBuffer = new StringBuilder();
        AtomicReference<String> assistantMessageId = new AtomicReference<>();
        AtomicReference<CacheMetrics> capturedMetrics = new AtomicReference<>();

        return chatProxy.streamChat(context.systemBlocks(), history, userMessage, capturedMetrics::set)
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
                        ChatMessage saved = chatRepository.save(assistantMsg);
                        assistantMessageId.set(saved.getId());

                        // Persist cache metrics asynchronously
                        CacheMetrics metrics = capturedMetrics.get();
                        if (metrics != null) {
                            try {
                                chatRepository.updateCacheMetrics(
                                        saved.getId(),
                                        metrics.wasCacheHit(),
                                        metrics.cacheReadInputTokens(),
                                        metrics.cacheCreationInputTokens(),
                                        metrics.inputTokens(),
                                        metrics.outputTokens()
                                );
                                log.info("[Cache] Turn saved ~${} in input cost",
                                        String.format("%.4f", metrics.estimateSavingUsd(3.00)));
                            } catch (Exception e) {
                                log.warn("[Cache] Failed to save metrics for message={}: {}", saved.getId(), e.getMessage());
                            }
                        }

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
