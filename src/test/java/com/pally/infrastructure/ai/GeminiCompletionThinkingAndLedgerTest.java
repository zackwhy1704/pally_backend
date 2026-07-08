package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.cost.AiTrigger;
import com.pally.domain.cost.AiUsageMeter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The Gemini completion seam carries the highest-COUNT AI calls. This test locks
 * three cost-truth invariants that the billing-screenshot investigation exposed:
 *
 * <ol>
 *   <li><b>Per-purpose thinking control</b> — extraction purposes disable thinking
 *       (thinkingBudget=0 in the request body); REASONING purposes (teach-eval)
 *       OMIT thinkingConfig so the provider default (thinking ON) stands. A blanket
 *       flip across task types is exactly what the plan forbade.</li>
 *   <li><b>Failure-path metering</b> — an empty-text response (Google STILL billed
 *       it) records a ledger row with success=false + the usageMetadata tokens,
 *       instead of throwing them away.</li>
 *   <li><b>Thinking tokens counted</b> — thoughtsTokenCount (billed as output, a
 *       separate field) is folded into output_tokens so the ledger matches the
 *       invoice.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class GeminiCompletionThinkingAndLedgerTest {

    @Mock private WebClient webClient;
    @Mock private ClaudeApiClient haikuFallback;
    @Mock private ModelRouter modelRouter;
    @Mock private AiUsageMeter aiUsageMeter;

    // The reactive chain: post().uri().header().bodyValue().retrieve().bodyToMono().block()
    @Mock private WebClient.RequestBodyUriSpec uriSpec;
    @Mock private WebClient.RequestBodySpec bodySpec;
    @SuppressWarnings("rawtypes")
    @Mock private WebClient.RequestHeadersSpec headersSpec;
    @Mock private WebClient.ResponseSpec responseSpec;

    private GeminiCompletionService service;
    private final ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);

    @BeforeEach
    void setUp() {
        // topic-router (extraction) → thinking OFF; teach-eval (reasoning) UNLISTED → thinking ON.
        GeminiThinkingBudgetConfig cfg = new GeminiThinkingBudgetConfig();
        cfg.setThinkingBudget(Map.of("topic-router", 0));
        service = new GeminiCompletionService(webClient, new ObjectMapper(),
                haikuFallback, modelRouter, aiUsageMeter, cfg);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
    }

    @SuppressWarnings("unchecked")
    private void stubGeminiResponse(String json) {
        when(webClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), anyString())).thenReturn(bodySpec);
        when(bodySpec.bodyValue(bodyCaptor.capture())).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(String.class)).thenReturn(Mono.just(json));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedGenerationConfig() {
        Map<String, Object> body = (Map<String, Object>) bodyCaptor.getValue();
        return (Map<String, Object>) body.get("generationConfig");
    }

    @Test
    void extractionPurpose_disablesThinking_inRequestBody() {
        stubGeminiResponse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"NOTES\"}]},"
                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,"
                + "\"candidatesTokenCount\":5,\"thoughtsTokenCount\":0}}");

        service.complete(256, "route this", "topic-router");

        Map<String, Object> gen = capturedGenerationConfig();
        assertThat(gen).containsKey("thinkingConfig");
        assertThat((Map<String, Object>) gen.get("thinkingConfig"))
                .containsEntry("thinkingBudget", 0);
    }

    @Test
    void reasoningPurpose_omitsThinkingConfig_soProviderDefaultStands() {
        stubGeminiResponse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"GOOD\"}]},"
                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":10,"
                + "\"candidatesTokenCount\":5,\"thoughtsTokenCount\":0}}");

        service.complete(1500, "evaluate this explanation", "teach-eval");

        // teach-eval is unlisted → thinkingConfig MUST be absent (thinking stays ON
        // until the evidence gate proves a flip is safe for reasoning tasks).
        assertThat(capturedGenerationConfig()).doesNotContainKey("thinkingConfig");
    }

    @Test
    void thinkingTokens_areFoldedIntoOutput_soLedgerMatchesInvoice() {
        stubGeminiResponse("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ANSWER\"}]},"
                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":100,"
                + "\"candidatesTokenCount\":50,\"thoughtsTokenCount\":200}}");

        service.complete(256, "summarise", "topic-router");

        // output = candidatesTokenCount (50) + thoughtsTokenCount (200) = 250; success.
        verify(aiUsageMeter).record(isNull(), isNull(), any(), eq("topic-router"),
                eq(AiTrigger.OTHER), anyString(), eq(100L), eq(250L), eq(true), eq(false));
    }

    @Test
    void emptyText_recordsFailedLedgerRowWithTokens_thenFallsBack() {
        // MAX_TOKENS with empty text: thinking ate the budget. Google billed the
        // prompt + thinking tokens; the row must be recorded (success=false) not lost.
        stubGeminiResponse("{\"candidates\":[{\"content\":{\"parts\":[{}]},"
                + "\"finishReason\":\"MAX_TOKENS\"}],\"usageMetadata\":{\"promptTokenCount\":100,"
                + "\"candidatesTokenCount\":0,\"thoughtsTokenCount\":700}}");
        when(modelRouter.getHaikuModel()).thenReturn("claude-haiku");
        when(haikuFallback.complete(anyString(), anyInt(), anyString(), anyString()))
                .thenReturn("haiku result");

        String out = service.complete(256, "route this", "topic-router");

        assertThat(out).isEqualTo("haiku result"); // fell back
        // failure metered: success=false, tokens present (in 100 / out 0+700), finishReason in label.
        verify(aiUsageMeter).record(isNull(), isNull(), any(),
                eq("topic-router:EMPTY:MAX_TOKENS"), eq(AiTrigger.OTHER), anyString(),
                eq(100L), eq(700L), eq(false), eq(false));
    }
}
