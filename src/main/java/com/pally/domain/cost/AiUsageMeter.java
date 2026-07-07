package com.pally.domain.cost;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Records one AI call's cost. BEST-EFFORT: {@link #record} NEVER throws — a
 * metering bug must never break or slow a compile/chat (mirrors the marking-
 * capture / notify pattern). userId may be null when the call has no user in
 * scope (e.g. a low-level Claude micro-call); the cost is still recorded.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiUsageMeter {

    private final AiUsageRepository repository;
    private final AiCostRates rates;

    public void record(String userId, AiCallType callType, String model,
                        long inputTokens, long outputTokens) {
        try {
            if (rates.rateFor(model) == null) {
                log.warn("[AiUsage] no cost rate for model={} — recording tokens with est_cost=0", model);
            }
            long cost = rates.estCostMicros(model, inputTokens, outputTokens);
            repository.save(AiUsage.of(userId, callType, model, inputTokens, outputTokens, cost));
        } catch (Exception e) {
            log.warn("[AiUsage] metering failed (non-fatal) model={} type={}: {}",
                    model, callType, e.toString());
        }
    }
}
