package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

/**
 * Gemini 1.5 Flash non-streaming completion — for classify / summarise tasks
 * that previously called Claude Haiku via {@code ClaudeApiClient.complete()}.
 *
 * <p>Routing: topic-router → Gemini → fallback Haiku
 *             session-summariser → Gemini → fallback Haiku
 *             teach evaluator → Gemini → fallback Haiku
 *
 * <p>Why Gemini for these tasks:
 * <ul>
 *   <li>Input $0.075/M vs Haiku $0.80/M — 10.7× cheaper</li>
 *   <li>Output $0.30/M vs Haiku $4.00/M — 13.3× cheaper</li>
 *   <li>Simple classify/summarise calls don't need streaming or caching</li>
 *   <li>Keeps chat streaming on Haiku (prompt-caching incompatible with Gemini)</li>
 * </ul>
 */
@Service
@Slf4j
public class GeminiCompletionService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    // Default to 1.5 flash (confirmed working; 2.0 requires paid key)
    private static final String COMPLETION_MODEL = "gemini-1.5-flash-latest";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ClaudeApiClient haikuFallback;
    private final ModelRouter modelRouter;

    public GeminiCompletionService(
            WebClient webClient,
            ObjectMapper objectMapper,
            ClaudeApiClient haikuFallback,
            ModelRouter modelRouter) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.haikuFallback = haikuFallback;
        this.modelRouter = modelRouter;
    }

    /**
     * Non-streaming completion: Gemini primary → Haiku fallback.
     *
     * @param maxTokens maximum output tokens
     * @param prompt    the full prompt text
     * @param task      log tag (e.g. "topic-router", "summarizer", "teach")
     * @return the model's text response, never null
     */
    public String complete(int maxTokens, String prompt, String task) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[GeminiCompletion] No API key — using Haiku for task={}", task);
            return haikuFallback.complete(modelRouter.getHaikuModel(), maxTokens, prompt, task);
        }

        long start = System.currentTimeMillis();
        try {
            String result = callGemini(maxTokens, prompt);
            log.debug("[GeminiCompletion] task={} latency={}ms chars={}",
                    task, System.currentTimeMillis() - start, result.length());
            return result;
        } catch (Exception e) {
            log.warn("[GeminiCompletion] task={} FAILED ({}): {} — falling back to Haiku",
                    task, e.getClass().getSimpleName(), e.getMessage());
            return haikuFallback.complete(modelRouter.getHaikuModel(), maxTokens, prompt, task);
        }
    }

    private String callGemini(int maxTokens, String prompt) throws Exception {
        String url = baseUrl
                + "/v1beta/models/" + COMPLETION_MODEL
                + ":generateContent?key=" + apiKey;

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", Map.of(
                        "maxOutputTokens", maxTokens,
                        "temperature", 0.1   // low temp for classify/summarise tasks
                )
        );

        String responseBody = webClient.post()
                .uri(url)
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(30));

        if (responseBody == null) {
            throw new RuntimeException("Gemini returned null response");
        }

        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode text = root
                .path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text");

        if (text.isMissingNode() || text.asText().isBlank()) {
            String finishReason = root.path("candidates").path(0)
                    .path("finishReason").asText("unknown");
            throw new RuntimeException("Empty text from Gemini, finishReason=" + finishReason);
        }
        return text.asText();
    }
}
