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

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Low-level WebClient wrapper for the Anthropic Messages API.
 *
 * <p>Supports both synchronous single-turn completion and asynchronous SSE streaming.
 * The streaming variant supports prompt caching via structured system blocks.
 */
@Component
@RequiredArgsConstructor
public class ClaudeApiClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeApiClient.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String MESSAGES_PATH = "/v1/messages";

    /// Bound every blocking Claude call so a hung Anthropic response can
    /// never park a server worker thread forever. Matches the
    /// WebClientConfig response-timeout with a small safety margin.
    private static final Duration UNARY_BLOCK_TIMEOUT = Duration.ofSeconds(70);
    /// Inter-chunk idle timeout for streaming. If Anthropic stops sending
    /// chunks for this long we fail the Flux instead of leaking the
    /// underlying socket. 45s is generous for big-context Sonnet replies.
    private static final Duration STREAM_IDLE_TIMEOUT = Duration.ofSeconds(45);

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
     * Used by harness micro-calls (relevance check, quiz generation, etc.).
     * Not cached — these calls are already cheap on Haiku.
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
                    .block(UNARY_BLOCK_TIMEOUT);
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
     * Streams a cached chat response using the Anthropic prompt caching API.
     *
     * <p>The {@code systemBlocks} parameter is a structured array where each block
     * is a {@code Map<String, Object>} with {@code type}, {@code text}, and optionally
     * {@code cache_control} fields. See {@link ClaudeContextAssembler} for block structure.
     *
     * <p>The {@code anthropic-beta: extended-cache-ttl-2025-04-11} header is required
     * for 1-hour TTL support. Without it, blocks with {@code "ttl":"1h"} fall back to 5 minutes.
     *
     * @param systemBlocks structured system content blocks with optional cache_control
     * @param messages     conversation turns — [{role, content}, ...]
     * @param maxTokens    maximum tokens in the response
     * @return Flux emitting raw SSE data lines; caller parses events
     */
    public Flux<String> streamResponseWithCache(
            List<Map<String, Object>> systemBlocks,
            List<Map<String, String>> messages,
            int maxTokens) {
        return streamResponseWithCacheAndModel(model, maxTokens, systemBlocks, messages);
    }

    /**
     * Same as {@link #streamResponseWithCache} but with an explicit model override.
     * Used by {@link CacheKeepAliveService} which uses Haiku for cheap keepalive pings.
     */
    public Flux<String> streamResponseWithCacheAndModel(
            String modelOverride,
            int maxTokens,
            List<Map<String, Object>> systemBlocks,
            List<Map<String, String>> messages) {

        String callId = UUID.randomUUID().toString().substring(0, 8);
        AtomicLong start = new AtomicLong(System.currentTimeMillis());
        AtomicInteger chunkCount = new AtomicInteger(0);

        log.info("[Claude-{}] STREAM REQUEST model={} maxTokens={} systemBlocks={} msgs={}",
                callId, modelOverride, maxTokens, systemBlocks.size(), messages.size());

        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", modelOverride);
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.set("system", objectMapper.valueToTree(systemBlocks));

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
                .header("anthropic-beta", ClaudeContextAssembler.BETA_HEADER_VALUE)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body.toString())
                .retrieve()
                .bodyToFlux(String.class)
                // Inter-emission idle timeout: if no chunk arrives within the
                // window, fail fast so the SSE controller can close the
                // client connection instead of leaking the underlying socket.
                .timeout(STREAM_IDLE_TIMEOUT, Flux.error(
                        new java.util.concurrent.TimeoutException(
                                "Claude stream idle for "
                                        + STREAM_IDLE_TIMEOUT.toSeconds() + "s")))
                .doOnNext(chunk -> chunkCount.incrementAndGet())
                .doOnComplete(() -> log.info("[Claude-{}] STREAM COMPLETE {}ms ~{}chunks",
                        callId, System.currentTimeMillis() - start.get(), chunkCount.get()))
                .doOnError(e -> log.error("[Claude-{}] STREAM ERROR after {}ms: {} — {}",
                        callId, System.currentTimeMillis() - start.get(),
                        e.getClass().getSimpleName(), e.getMessage()));
    }

    /**
     * Legacy streaming method (string system prompt, no caching).
     * Kept for backward compatibility — prefer {@link #streamResponseWithCache}.
     */
    public Flux<String> streamResponse(String systemPrompt, List<Map<String, String>> messages, int maxTokens) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        AtomicLong start = new AtomicLong(System.currentTimeMillis());
        AtomicInteger chunkCount = new AtomicInteger(0);

        log.info("[Claude-{}] STREAM REQUEST (legacy) model={} maxTokens={} messageCount={}", callId, model, maxTokens, messages.size());

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
                .timeout(STREAM_IDLE_TIMEOUT, Flux.error(
                        new java.util.concurrent.TimeoutException(
                                "Claude stream idle for "
                                        + STREAM_IDLE_TIMEOUT.toSeconds() + "s")))
                .doOnNext(chunk -> chunkCount.incrementAndGet())
                .doOnComplete(() -> log.info("[Claude-{}] STREAM COMPLETE {}ms ~{}chunks",
                        callId, System.currentTimeMillis() - start.get(), chunkCount.get()))
                .doOnError(e -> log.error("[Claude-{}] STREAM ERROR after {}ms: {} — {}",
                        callId, System.currentTimeMillis() - start.get(),
                        e.getClass().getSimpleName(), e.getMessage()));
    }
}
