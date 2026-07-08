package com.pally.domain.cost;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * One AI call's cost record. {@code userId}/{@code avatarId} are nullable — some
 * low-level calls lack them in scope; record null rather than dropping the cost.
 * {@code purposeLabel} is the FINE label (call_type is the coarse category);
 * {@code estimated} = token counts are char-estimates (no provider metadata).
 * {@code estCostMicros} is millionths of a USD — a RELATIVE estimate, not the bill.
 */
public record AiUsage(
        String id,
        String userId,
        String avatarId,
        AiCallType callType,
        String purposeLabel,
        AiTrigger trigger,
        String model,
        long inputTokens,
        long outputTokens,
        long estCostMicros,
        boolean success,
        boolean estimated,
        Instant createdAt) {

    public static AiUsage of(String userId, String avatarId, AiCallType callType,
                             String purposeLabel, AiTrigger trigger, String model,
                             long inputTokens, long outputTokens, long estCostMicros,
                             boolean success, boolean estimated) {
        return new AiUsage(IdGenerator.newId(), userId, avatarId, callType,
                purposeLabel, trigger, model, inputTokens, outputTokens,
                estCostMicros, success, estimated, Instant.now());
    }
}
