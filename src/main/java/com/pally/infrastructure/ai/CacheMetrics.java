package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record CacheMetrics(
        int inputTokens,
        int outputTokens,
        int cacheCreationInputTokens,
        int cacheReadInputTokens,
        boolean wasCacheHit
) {
    public static CacheMetrics fromUsageJson(JsonNode usage) {
        return new CacheMetrics(
                usage.path("input_tokens").asInt(0),
                usage.path("output_tokens").asInt(0),
                usage.path("cache_creation_input_tokens").asInt(0),
                usage.path("cache_read_input_tokens").asInt(0),
                usage.path("cache_read_input_tokens").asInt(0) > 0
        );
    }

    /** Estimated saving vs no-cache in USD (Sonnet 4.6: $3.00/M input). */
    public double estimateSavingUsd(double inputPricePerMillion) {
        double savingPerToken = inputPricePerMillion * 0.90 / 1_000_000.0;
        return cacheReadInputTokens * savingPerToken;
    }

    public String toLogLine() {
        return String.format(
                "input=%d output=%d cacheWrite=%d cacheRead=%d hit=%b saving~$%.5f",
                inputTokens, outputTokens,
                cacheCreationInputTokens, cacheReadInputTokens,
                wasCacheHit,
                estimateSavingUsd(3.00));
    }

    public static final CacheMetrics EMPTY = new CacheMetrics(0, 0, 0, 0, false);
}
