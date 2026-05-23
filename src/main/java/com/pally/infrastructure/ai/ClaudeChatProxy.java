package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatStreamEvent;
import com.pally.domain.chat.port.ChatPort;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Claude-backed implementation of {@link ChatPort}.
 *
 * <p>Builds a message array from chat history and the current user message,
 * then streams the response from Claude via SSE, mapping each delta event
 * to a {@link ChatStreamEvent}.
 */
@Component
@RequiredArgsConstructor
public class ClaudeChatProxy implements ChatPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudeChatProxy.class);
    private static final int MAX_TOKENS = 1024;

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;

    @Override
    public Flux<ChatStreamEvent> streamChat(String systemPrompt, List<ChatMessage> history, String userMessage) {
        List<Map<String, String>> messages = buildMessages(history, userMessage);

        log.debug("Starting chat stream messageCount={}", messages.size());

        return apiClient.streamResponse(systemPrompt, messages, MAX_TOKENS)
                .flatMap(this::parseEvent)
                .onErrorResume(e -> {
                    log.error("Chat stream error", e);
                    return Flux.just(new ChatStreamEvent.Error(e.getMessage()));
                });
    }

    private List<Map<String, String>> buildMessages(List<ChatMessage> history, String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage msg : history) {
            messages.add(Map.of(
                    "role", msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                    "content", msg.getContent()
            ));
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    private Flux<ChatStreamEvent> parseEvent(String line) {
        if (line == null || line.isBlank()) {
            return Flux.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText();

            return switch (type) {
                case "content_block_delta" -> {
                    String text = node.path("delta").path("text").asText("");
                    yield text.isBlank() ? Flux.empty() : Flux.just(new ChatStreamEvent.Token(text));
                }
                case "message_stop" -> Flux.just(new ChatStreamEvent.Done(null));
                case "error" -> {
                    String msg = node.path("error").path("message").asText("Unknown error");
                    yield Flux.just(new ChatStreamEvent.Error(msg));
                }
                default -> Flux.empty();
            };
        } catch (Exception e) {
            log.trace("Skipping unparseable SSE line: {}", line);
            return Flux.empty();
        }
    }
}
