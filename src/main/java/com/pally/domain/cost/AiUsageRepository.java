package com.pally.domain.cost;

import java.time.Instant;
import java.util.List;

/** Port for AI cost records + the one rollup the admin endpoint needs. */
public interface AiUsageRepository {

    AiUsage save(AiUsage usage);

    /**
     * Cost summed per (user, callType) over [from, to). The controller groups
     * these into per-user totals ordered by cost. One aggregate query — no
     * per-request drill-down.
     */
    List<CostRow> summarize(Instant from, Instant to);

    /** userId may be null (calls without a user). */
    record CostRow(String userId, AiCallType callType, long totalCostMicros, long callCount) {}
}
