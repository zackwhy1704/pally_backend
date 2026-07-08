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

    // gemini-1.5-* was retired by Google (404s) — current multimodal flash.
    private static final String COMPLETION_MODEL = "gemini-2.5-flash";

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ClaudeApiClient haikuFallback;
    private final ModelRouter modelRouter;
    private final com.pally.domain.cost.AiUsageMeter aiUsageMeter;
    private final GeminiThinkingBudgetConfig thinkingConfig;

    public GeminiCompletionService(
            WebClient webClient,
            ObjectMapper objectMapper,
            ClaudeApiClient haikuFallback,
            ModelRouter modelRouter,
            com.pally.domain.cost.AiUsageMeter aiUsageMeter,
            GeminiThinkingBudgetConfig thinkingConfig) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.haikuFallback = haikuFallback;
        this.modelRouter = modelRouter;
        this.aiUsageMeter = aiUsageMeter;
        this.thinkingConfig = thinkingConfig;
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
        return complete(maxTokens, prompt, task, null);
    }

    /** Attribution overload: callers that know the avatar (module gen, teach,
     *  PROVE eval) pass it so the cost ledger row carries avatar_id — this closes
     *  the record(null,null) gap for the GeminiCompletionService seam. */
    public String complete(int maxTokens, String prompt, String task, String avatarId) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("[GeminiCompletion] No API key — using Haiku for task={}", task);
            return haikuFallback.complete(modelRouter.getHaikuModel(), maxTokens, prompt, task);
        }

        long start = System.currentTimeMillis();
        try {
            String result = callGemini(maxTokens, prompt, task, avatarId);
            log.debug("[GeminiCompletion] task={} latency={}ms chars={}",
                    task, System.currentTimeMillis() - start, result.length());
            return result;
        } catch (Exception e) {
            log.warn("[GeminiCompletion] task={} FAILED ({}): {} — falling back to Haiku",
                    task, e.getClass().getSimpleName(), e.getMessage());
            return haikuFallback.complete(modelRouter.getHaikuModel(), maxTokens, prompt, task);
        }
    }

    private String callGemini(int maxTokens, String prompt, String task, String avatarId) throws Exception {
        String url = baseUrl
                + "/v1beta/models/" + COMPLETION_MODEL
                + ":generateContent?key=" + apiKey;

        // Per-purpose thinking control (GeminiThinkingBudgetConfig): extraction /
        // classify / structured-generation purposes are configured to 0 (thinking
        // OFF — reliable output, no billed thinking tokens); REASONING purposes
        // (teach-eval, module-prove-eval) are UNLISTED → we omit thinkingConfig so
        // the provider default (thinking ON) stands until an evidence gate proves a
        // flip is safe. Never a blanket flip across task types.
        Map<String, Object> generationConfig = new java.util.HashMap<>();
        generationConfig.put("maxOutputTokens", maxTokens);
        generationConfig.put("temperature", 0.1);  // low temp for classify/summarise tasks
        Integer budget = thinkingConfig.budgetFor(task);
        if (budget != null) {
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", budget));
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", List.of(Map.of("text", prompt))
                )),
                "generationConfig", generationConfig
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
        // usageMetadata rides in this SAME body on BOTH the success and the empty-text
        // path — hoist it so a failed call is metered, not discarded (Google billed it).
        JsonNode usage = root.path("usageMetadata");
        JsonNode text = root
                .path("candidates").path(0)
                .path("content").path("parts").path(0)
                .path("text");

        if (text.isMissingNode() || text.asText().isBlank()) {
            String finishReason = root.path("candidates").path(0)
                    .path("finishReason").asText("unknown");
            // Google STILL BILLED this call — the thinking/prompt tokens were consumed
            // even though the output came back empty (typically MAX_TOKENS: thinking ate
            // the budget; or SAFETY: a content refusal). Record success=false WITH the
            // usageMetadata tokens BEFORE throwing — failed calls are exactly the spend
            // you most need visible. finishReason rides in the purpose_label suffix
            // (MAX_TOKENS vs SAFETY distinguishes budget-exhaustion from a refusal), so
            // it needs no schema migration and reverts by deleting one string.
            meter(avatarId, task + ":EMPTY:" + finishReason, prompt, "", usage, false);
            throw new RuntimeException("Empty text from Gemini, finishReason=" + finishReason);
        }
        String out = text.asText();
        meter(avatarId, task, prompt, out, usage, true);
        return out;
    }

    /**
     * Cost ledger for one Gemini call. No user/avatar in scope at this seam → null;
     * the fine {@code task} is the purpose_label. Char-estimate (chars/4) only if the
     * provider omitted usage, flagged via {@code estimated}. THINKING tokens are billed
     * at the OUTPUT rate but arrive in a SEPARATE field (thoughtsTokenCount) that
     * candidatesTokenCount excludes — folding them in stops the ~3x output under-count
     * (320k ledgered vs ~1M on Google's dashboard). Best-effort — record() never throws.
     */
    private void meter(String avatarId, String label, String prompt, String out,
                       JsonNode usage, boolean success) {
        boolean measured = !usage.isMissingNode();
        long inTok = measured ? usage.path("promptTokenCount").asLong(0) : prompt.length() / 4L;
        long thoughtsTok = measured ? usage.path("thoughtsTokenCount").asLong(0) : 0L;
        long outTok = (measured ? usage.path("candidatesTokenCount").asLong(0) : out.length() / 4L)
                + thoughtsTok;
        aiUsageMeter.record(null, avatarId, com.pally.domain.cost.AiCallType.fromLabel(label),
                label, com.pally.domain.cost.AiTrigger.OTHER, COMPLETION_MODEL,
                inTok, outTok, success, !measured);
    }
}
