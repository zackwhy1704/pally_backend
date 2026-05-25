package com.pally.domain.chat.port;

import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.infrastructure.ai.CacheMetrics;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Port for streaming AI chat responses with prompt caching support.
 */
public interface ChatPort {

    /**
     * Streams a chat response using structured system blocks (prompt caching enabled).
     *
     * @param systemBlocks ordered cache-control blocks from ClaudeContextAssembler
     * @param history      recent chat history (oldest-first)
     * @param userMessage  the latest user message
     * @param onMetrics    callback invoked when cache metrics are available (may be null)
     * @return a reactive stream of {@link ChatStreamEvent} instances
     */
    Flux<ChatStreamEvent> streamChat(
            List<Map<String, Object>> systemBlocks,
            List<ChatMessage> history,
            String userMessage,
            Consumer<CacheMetrics> onMetrics);
}
