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

    /** Back-compat convenience: coarse category only, purpose_label = the category
     *  name, trigger OTHER, success true, measured tokens. */
    public void record(String userId, AiCallType callType, String model,
                       long inputTokens, long outputTokens) {
        record(userId, null, callType, callType.name(), AiTrigger.OTHER, model,
                inputTokens, outputTokens, true, false);
    }

    /** Full record — the completion-service seam passes the fine label, trigger,
     *  success, and whether tokens were measured (usageMetadata) vs char-estimated.
     *  BEST-EFFORT: NEVER throws — a metering bug must not break the parent call. */
    public void record(String userId, String avatarId, AiCallType callType,
                       String purposeLabel, AiTrigger trigger, String model,
                       long inputTokens, long outputTokens, boolean success,
                       boolean estimated) {
        try {
            if (rates.rateFor(model) == null) {
                log.warn("[AiUsage] no cost rate for model={} — recording tokens with est_cost=0", model);
            }
            long cost = rates.estCostMicros(model, inputTokens, outputTokens);
            repository.save(AiUsage.of(userId, avatarId, callType, purposeLabel,
                    trigger == null ? AiTrigger.OTHER : trigger, model,
                    inputTokens, outputTokens, cost, success, estimated));
        } catch (Exception e) {
            log.warn("[AiUsage] metering failed (non-fatal) model={} label={}: {}",
                    model, purposeLabel, e.toString());
        }
    }
}
