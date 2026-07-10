package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.cost.AiUsageMeter;
import com.pally.infrastructure.observability.ClaudeMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * Pins the BILLED-BUT-FAILED metering fix: when Anthropic bills a 200 but we can't use
 * it (parse failure / empty content array), the cost ledger must still get a
 * success=false row WITH the billed tokens — never silently drop real spend.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeApiClientTest {

    @Mock WebClient webClient;
    @Mock ClaudeMetrics metrics;
    @Mock AiUsageMeter meter;

    ClaudeApiClient client;

    @BeforeEach
    void setUp() {
        client = new ClaudeApiClient(webClient, new ObjectMapper(), metrics, meter);
    }

    @Test
    void billedFailure_recordsSuccessFalse_withTheBilledTokens() {
        // A real generation that we then failed to use — usage present, content empty.
        String body = "{\"usage\":{\"input_tokens\":120,\"output_tokens\":45},\"content\":[]}";

        client.meterBilledFailure(body, "COMPILE", "claude-x");

        verify(meter).record(isNull(), isNull(), any(), eq("COMPILE"), any(),
                eq("claude-x"), eq(120L), eq(45L), eq(false), eq(false));
    }

    @Test
    void billedFailure_unreadableBody_stillRecordsFalse_atZeroTokens() {
        client.meterBilledFailure("<<garbage not json>>", "CHAT", "claude-x");

        verify(meter).record(isNull(), isNull(), any(), eq("CHAT"), any(),
                eq("claude-x"), eq(0L), eq(0L), eq(false), eq(false));
    }
}
