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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude-backed implementation of {@link ChatPort}.
 *
 * <p>Uses structured system blocks for prompt caching (Blocks 1–3 cached,
 * Block 4 uncached). Extracts cache metrics from the SSE stream's usage events
 * and forwards them via the {@code onMetrics} callback.
 */
@Component
@RequiredArgsConstructor
public class ClaudeChatProxy implements ChatPort {

    private static final Logger log = LoggerFactory.getLogger(ClaudeChatProxy.class);
    private static final int MAX_TOKENS = 1024;
    // Matches the trailing "SOURCE: <slug>" line Claude appends per system
    // prompt. We strip it before the user ever sees it and surface the slug
    // separately via {@link ChatStreamEvent.Done#sourceFile()}.
    private static final Pattern SOURCE_LINE = Pattern.compile(
            "\\s*\\n*SOURCE\\s*:?\\s*\\[?([a-zA-Z0-9_\\-/]+)\\]?\\s*$");

    private final ClaudeApiClient apiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;

    @Override
    public Flux<ChatStreamEvent> streamChat(
            List<Map<String, Object>> systemBlocks,
            List<ChatMessage> history,
            String userMessage,
            Consumer<CacheMetrics> onMetrics) {

        String model = modelRouter.forChat(userMessage);
        List<Map<String, String>> messages = buildMessages(history, userMessage);
        AtomicBoolean metricsEmitted = new AtomicBoolean(false);
        // Accumulate the full assistant text so we can parse the trailing
        // "SOURCE: <slug>" Claude appends. The slug then rides on the Done
        // event instead of leaking into the rendered bubble.
        StringBuilder fullText = new StringBuilder();

        log.debug("Starting cached chat stream model={} messageCount={} systemBlocks={}",
                model, messages.size(), systemBlocks.size());

        return apiClient.streamResponseWithCacheAndModel(model, MAX_TOKENS, systemBlocks, messages)
                .flatMap(line -> parseEventWithMetrics(line, onMetrics, metricsEmitted, fullText))
                .onErrorResume(e -> {
                    if (!model.equals(modelRouter.getHaikuModel())) {
                        log.warn("[Chat] Sonnet failed ({}), retrying with Haiku: {}",
                                model, e.getMessage());
                        fullText.setLength(0);
                        return apiClient.streamResponseWithCacheAndModel(
                                        modelRouter.getHaikuModel(), MAX_TOKENS,
                                        systemBlocks, messages)
                                .flatMap(line -> parseEventWithMetrics(
                                        line, onMetrics, metricsEmitted, fullText));
                    }
                    log.error("Chat stream error", e);
                    return Flux.just(new ChatStreamEvent.Error(e.getMessage()));
                });
    }

    private List<Map<String, String>> buildMessages(List<ChatMessage> history, String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        for (ChatMessage msg : history) {
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            messages.add(Map.of(
                    "role", msg.getRole() == ChatMessage.Role.USER ? "user" : "assistant",
                    "content", msg.getContent()
            ));
        }
        messages.add(Map.of("role", "user", "content", userMessage));
        return messages;
    }

    private Flux<ChatStreamEvent> parseEventWithMetrics(
            String line,
            Consumer<CacheMetrics> onMetrics,
            AtomicBoolean metricsEmitted,
            StringBuilder fullText) {

        if (line == null || line.isBlank()) return Flux.empty();
        try {
            JsonNode node = objectMapper.readTree(line);
            String type = node.path("type").asText();

            // Extract cache metrics from message_delta usage (final usage block)
            if (onMetrics != null && !metricsEmitted.get() &&
                    ("message_delta".equals(type) || "message_stop".equals(type))) {
                JsonNode usage = node.path("usage");
                if (!usage.isMissingNode()) {
                    CacheMetrics metrics = CacheMetrics.fromUsageJson(usage);
                    log.info("[CacheMetrics] {}", metrics.toLogLine());
                    metricsEmitted.set(true);
                    onMetrics.accept(metrics);
                }
            }

            return switch (type) {
                case "content_block_delta" -> {
                    String text = node.path("delta").path("text").asText("");
                    if (text.isBlank()) {
                        yield Flux.empty();
                    }
                    fullText.append(text);
                    yield Flux.just(new ChatStreamEvent.Token(text));
                }
                case "message_stop" -> {
                    String slug = extractSourceSlug(fullText.toString());
                    yield Flux.just(new ChatStreamEvent.Done(slug));
                }
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

    /**
     * Returns the slug from the trailing {@code SOURCE: <slug>} line, or
     * {@code null} if no source marker was found.
     */
    private String extractSourceSlug(String text) {
        if (text == null || text.isEmpty()) return null;
        Matcher m = SOURCE_LINE.matcher(text);
        if (!m.find()) return null;
        String slug = m.group(1);
        return "general-knowledge".equalsIgnoreCase(slug) ? null : slug;
    }
}
