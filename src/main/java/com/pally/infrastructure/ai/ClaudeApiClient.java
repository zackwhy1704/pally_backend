package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Low-level WebClient wrapper for the Anthropic Messages API.
 *
 * <p>Supports both synchronous single-turn completion and asynchronous SSE streaming.
 * All requests include the required {@code anthropic-version: 2023-06-01} header.
 */
@Component
@RequiredArgsConstructor
public class ClaudeApiClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeApiClient.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String MESSAGES_PATH = "/v1/messages";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${claude.api.key}")
    private String apiKey;

    @Value("${claude.api.base-url}")
    private String baseUrl;

    @Value("${claude.api.model}")
    private String model;

    /**
     * Sends a single-turn completion request and returns the assistant's text response.
     *
     * @param model     model identifier (e.g. "claude-3-5-sonnet-20241022")
     * @param maxTokens maximum tokens in the response
     * @param prompt    user-turn text prompt
     * @return the assistant's full text response
     */
    public String complete(String model, int maxTokens, String prompt) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        long start = System.currentTimeMillis();

        log.info("[Claude-{}] REQUEST model={} maxTokens={} promptChars={}", callId, model, maxTokens, prompt.length());
        log.debug("[Claude-{}] Prompt preview: {}", callId, prompt.substring(0, Math.min(200, prompt.length())));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        String responseJson;
        try {
            responseJson = webClient.post()
                    .uri(baseUrl + MESSAGES_PATH)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .doOnError(e -> log.error("[Claude-{}] FAILED after {}ms: {} — {}",
                            callId, System.currentTimeMillis() - start,
                            e.getClass().getSimpleName(), e.getMessage()))
                    .block();
        } catch (Exception e) {
            throw e;
        }

        long ms = System.currentTimeMillis() - start;
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String text = root.path("content").get(0).path("text").asText();
            log.info("[Claude-{}] RESPONSE {}ms responseChars={}", callId, ms, text.length());
            log.debug("[Claude-{}] Response preview: {}", callId, text.substring(0, Math.min(300, text.length())));
            return text;
        } catch (Exception e) {
            log.error("[Claude-{}] PARSE FAILED after {}ms: {}", callId, ms, responseJson, e);
            throw new RuntimeException("Unexpected Claude API response format", e);
        }
    }

    /**
     * Streams a response from Claude using server-sent events.
     *
     * @param systemPrompt the system prompt for the assistant's persona
     * @param messages     ordered list of message maps with "role" and "content" keys
     * @param maxTokens    maximum tokens in the response
     * @return a {@link Flux} emitting raw SSE data lines from the API
     */
    public Flux<String> streamResponse(String systemPrompt, List<Map<String, String>> messages, int maxTokens) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        AtomicLong start = new AtomicLong(System.currentTimeMillis());
        AtomicInteger chunkCount = new AtomicInteger(0);

        log.info("[Claude-{}] STREAM REQUEST model={} maxTokens={} messageCount={}", callId, model, maxTokens, messages.size());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.put("system", systemPrompt);

        ArrayNode messagesNode = body.putArray("messages");
        for (Map<String, String> msg : messages) {
            ObjectNode msgNode = messagesNode.addObject();
            msgNode.put("role", msg.get("role"));
            msgNode.put("content", msg.get("content"));
        }

        return webClient.post()
                .uri(baseUrl + MESSAGES_PATH)
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                .doOnNext(chunk -> chunkCount.incrementAndGet())
                .doOnComplete(() -> log.info("[Claude-{}] STREAM COMPLETE {}ms ~{}chunks",
                        callId, System.currentTimeMillis() - start.get(), chunkCount.get()))
                .doOnError(e -> log.error("[Claude-{}] STREAM ERROR after {}ms: {} — {}",
                        callId, System.currentTimeMillis() - start.get(),
                        e.getClass().getSimpleName(), e.getMessage()));
    }
}
