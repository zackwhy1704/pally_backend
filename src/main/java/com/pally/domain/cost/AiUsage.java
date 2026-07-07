package com.pally.domain.cost;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * One AI call's cost record. {@code userId} is nullable — some low-level calls
 * lack a user in scope; record null rather than dropping the cost.
 * {@code estCostMicros} is millionths of a USD (tokens x per-model rates) — a
 * RELATIVE estimate for attribution, not the provider's actual bill.
 */
public record AiUsage(
        String id,
        String userId,
        AiCallType callType,
        String model,
        long inputTokens,
        long outputTokens,
        long estCostMicros,
        Instant createdAt) {

    public static AiUsage of(String userId, AiCallType callType, String model,
                             long inputTokens, long outputTokens, long estCostMicros) {
        return new AiUsage(IdGenerator.newId(), userId, callType, model,
                inputTokens, outputTokens, estCostMicros, Instant.now());
    }
}
