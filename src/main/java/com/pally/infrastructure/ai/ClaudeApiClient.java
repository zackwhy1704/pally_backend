package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pally.infrastructure.observability.ClaudeMetrics;
import com.pally.shared.exception.BusinessException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private final ClaudeMetrics metrics;

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
        // task tag derives from the model role — chat/wiki callers go
        // through the streaming methods, so a unary call here is most
        // likely a Haiku micro-task (relevance, quiz gen, conflict check).
        return complete(model, maxTokens, prompt, "haiku-micro");
    }

    /// Same as {@link #complete} but emits a {@code task} tag on the
    /// metrics so dashboards can split spend by feature. Wrapped in
    /// Retry + CircuitBreaker; the fallback NEVER returns fabricated
    /// content (see {@link #completeFallback}).
    @Retry(name = "claude")
    @CircuitBreaker(name = "claude", fallbackMethod = "completeFallback")
    public String complete(String model, int maxTokens, String prompt, String task) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        long start = System.currentTimeMillis();
        Timer.Sample sample = metrics.startLatency();

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
            metrics.recordError(task, e.getClass().getSimpleName());
            metrics.stopLatency(sample, task, model);
            throw e;
        }

        long ms = System.currentTimeMillis() - start;
        try {
            JsonNode root = objectMapper.readTree(responseJson);
            String text = root.path("content").get(0).path("text").asText();
            // Anthropic returns usage.{input_tokens,output_tokens} on
            // every response. Defaults to 0 so a missing field doesn't
            // throw — metrics are best-effort observability.
            long inTok = root.path("usage").path("input_tokens").asLong(0);
            long outTok = root.path("usage").path("output_tokens").asLong(0);
            metrics.recordTokens(task, model, inTok, outTok);
            metrics.stopLatency(sample, task, model);
            log.info("[Claude-{}] RESPONSE {}ms responseChars={} in={} out={}",
                    callId, ms, text.length(), inTok, outTok);
            log.debug("[Claude-{}] Response preview: {}", callId, text.substring(0, Math.min(300, text.length())));
            return text;
        } catch (Exception e) {
            metrics.recordError(task, "parse_error");
            metrics.stopLatency(sample, task, model);
            log.error("[Claude-{}] PARSE FAILED after {}ms: {}", callId, ms, responseJson, e);
            throw new RuntimeException("Unexpected Claude API response format", e);
        }
    }

    // ── Tool-use (agentic loop) ────────────────────────────────────────────────

    /**
     * Sends a completion request with one or more deterministic tools available.
     * Runs the Anthropic tool-use agentic loop until:
     *  1. The model returns {@code stop_reason == "end_turn"} (finished), or
     *  2. {@code MAX_TOOL_ITERATIONS} is reached, or
     *  3. The total wall-clock time exceeds {@code TOOL_LOOP_TIMEOUT}.
     *
     * <p>On time-out the best available text from the last call is returned
     * with a logged warning — the loop never hangs the request thread.
     * The circuit breaker wraps the full loop.
     */
    private static final int MAX_TOOL_ITERATIONS = 3;
    private static final Duration TOOL_LOOP_TIMEOUT = Duration.ofSeconds(30);

    @Retry(name = "claude")
    @CircuitBreaker(name = "claude", fallbackMethod = "completeWithToolsFallback")
    public String completeWithTools(String modelStr, int maxTokens, String prompt,
                                    List<ClaudeTool> tools, String task) {
        String callId = UUID.randomUUID().toString().substring(0, 8);
        Instant deadline = Instant.now().plus(TOOL_LOOP_TIMEOUT);
        Timer.Sample sample = metrics.startLatency();

        log.info("[Claude-{}] TOOL-USE REQUEST model={} task={} tools={} promptChars={}",
                callId, modelStr, task, tools.stream().map(ClaudeTool::name).toList(),
                prompt.length());

        // Build tools array for the request
        ArrayNode toolsArray = objectMapper.createArrayNode();
        for (ClaudeTool tool : tools) {
            ObjectNode toolNode = toolsArray.addObject();
            toolNode.put("name", tool.name());
            toolNode.put("description", tool.description());
            toolNode.set("input_schema", objectMapper.valueToTree(tool.inputSchema()));
        }

        // Initial messages: just the user prompt
        ArrayNode messages = objectMapper.createArrayNode();
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", prompt);

        String lastText = null;
        int iterations = 0;

        try {
            while (iterations < MAX_TOOL_ITERATIONS) {
                if (Instant.now().isAfter(deadline)) {
                    log.warn("[Claude-{}] Tool loop time-boxed after {}ms (iteration {}); "
                            + "returning best text answer",
                            callId, TOOL_LOOP_TIMEOUT.toMillis(), iterations);
                    break;
                }
                iterations++;

                ObjectNode body = objectMapper.createObjectNode();
                body.put("model", modelStr);
                body.put("max_tokens", maxTokens);
                body.set("tools", toolsArray);
                body.set("messages", messages);

                String responseJson = webClient.post()
                        .uri(baseUrl + MESSAGES_PATH)
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body.toString())
                        .retrieve()
                        .bodyToMono(String.class)
                        .block(UNARY_BLOCK_TIMEOUT);

                JsonNode root = objectMapper.readTree(responseJson);
                String stopReason = root.path("stop_reason").asText("end_turn");

                // Collect text + tool_use blocks from this response
                ArrayNode contentArray = (ArrayNode) root.path("content");
                StringBuilder textAccum = new StringBuilder();
                List<JsonNode> toolUseBlocks = new ArrayList<>();

                for (JsonNode block : contentArray) {
                    String type = block.path("type").asText();
                    if ("text".equals(type)) {
                        textAccum.append(block.path("text").asText());
                    } else if ("tool_use".equals(type)) {
                        toolUseBlocks.add(block);
                    }
                }
                if (!textAccum.isEmpty()) lastText = textAccum.toString().strip();

                // Record tokens
                long inTok = root.path("usage").path("input_tokens").asLong(0);
                long outTok = root.path("usage").path("output_tokens").asLong(0);
                metrics.recordTokens(task, modelStr, inTok, outTok);

                if ("end_turn".equals(stopReason) || toolUseBlocks.isEmpty()) {
                    log.info("[Claude-{}] Tool loop done after {} iterations", callId, iterations);
                    break;
                }

                // Append assistant turn (must mirror what Claude sent, verbatim)
                ObjectNode assistantTurn = messages.addObject();
                assistantTurn.put("role", "assistant");
                assistantTurn.set("content", contentArray);

                // Execute each tool and collect results
                ArrayNode toolResults = objectMapper.createArrayNode();
                for (JsonNode toolUse : toolUseBlocks) {
                    String toolName = toolUse.path("name").asText();
                    String toolUseId = toolUse.path("id").asText();
                    JsonNode inputNode = toolUse.path("input");

                    String toolResult = executeToolCall(tools, toolName, inputNode, callId);

                    ObjectNode resultBlock = toolResults.addObject();
                    resultBlock.put("type", "tool_result");
                    resultBlock.put("tool_use_id", toolUseId);
                    resultBlock.put("content", toolResult);
                }

                // Append user turn with tool results
                ObjectNode userResultTurn = messages.addObject();
                userResultTurn.put("role", "user");
                userResultTurn.set("content", toolResults);
            }
        } catch (Exception e) {
            metrics.recordError(task, e.getClass().getSimpleName());
            metrics.stopLatency(sample, task, modelStr);
            throw new RuntimeException("Tool-use loop failed: " + e.getMessage(), e);
        }

        metrics.stopLatency(sample, task, modelStr);
        if (lastText == null) lastText = "";
        log.info("[Claude-{}] Tool-use complete text={} chars iterations={}",
                callId, lastText.length(), iterations);
        return lastText;
    }

    private String executeToolCall(List<ClaudeTool> tools, String toolName,
                                    JsonNode inputNode, String callId) {
        for (ClaudeTool tool : tools) {
            if (tool.name().equals(toolName)) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> input = objectMapper.convertValue(
                            inputNode, Map.class);
                    String result = tool.execute(input);
                    log.info("[Claude-{}] Tool {} → {}", callId, toolName, result);
                    metrics.recordToolCall(toolName);
                    return result;
                } catch (Exception e) {
                    log.warn("[Claude-{}] Tool {} failed: {}", callId, toolName, e.getMessage());
                    metrics.recordToolError(toolName);
                    return "Error: " + e.getMessage();
                }
            }
        }
        log.warn("[Claude-{}] Unknown tool requested: {}", callId, toolName);
        return "Error: unknown tool '" + toolName + "'";
    }

    @SuppressWarnings("unused")
    public String completeWithToolsFallback(String modelStr, int maxTokens, String prompt,
                                            List<ClaudeTool> tools, String task, Throwable cause) {
        log.warn("[Claude] tool-use fallback fired task={} cause={}",
                task, cause == null ? "null" : cause.getMessage());
        metrics.recordError(task, "circuit_open");
        throw new BusinessException(
                "Mochi's resting for a moment — try again shortly.", 503);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /// Resilience4j fallback for {@link #complete(String, int, String, String)}.
    /// Called when the breaker is OPEN or retries are exhausted. Throws a
    /// 503 BusinessException with a friendly message — NEVER fabricates
    /// tutoring content, per the "never fabricate" rule that survived
    /// the stub-removal audit. Callers translate this into the same
    /// PallyError the client already knows.
    @SuppressWarnings("unused")
    public String completeFallback(String model, int maxTokens, String prompt,
                                   String task, Throwable cause) {
        log.warn("[Claude] fallback fired task={} model={} cause={}: {}",
                task, model,
                cause == null ? "null" : cause.getClass().getSimpleName(),
                cause == null ? "" : cause.getMessage());
        metrics.recordError(task, "circuit_open");
        throw new BusinessException(
                "Mochi's resting for a moment — try again shortly.", 503);
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
