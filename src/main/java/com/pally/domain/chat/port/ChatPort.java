package com.pally.domain.chat.port;

import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatStreamEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Port for streaming AI chat responses.
 */
public interface ChatPort {

    /**
     * Streams a chat response from the AI model.
     *
     * @param systemPrompt  the avatar's persona / knowledge system prompt
     * @param history       recent chat history (oldest-first)
     * @param userMessage   the latest user message
     * @return a reactive stream of {@link ChatStreamEvent} instances
     */
    Flux<ChatStreamEvent> streamChat(String systemPrompt, List<ChatMessage> history, String userMessage);
}
