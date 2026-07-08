package com.pally.api.admin;

import com.pally.domain.cost.AiCallType;
import com.pally.domain.cost.AiUsageRepository;
import com.pally.domain.cost.AiUsageRepository.CostRow;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ONE per-user AI-cost rollup — "who is expensive, and by which feature".
 * Admin-only (SecurityConfig gates {@code /api/v1/admin/**} with hasRole("ADMIN")).
 * Deliberately minimal: one aggregate, no charts, no per-request drill-down.
 * est_cost is a RELATIVE estimate (tokens x rates), not a provider-invoice
 * reconciliation.
 *
 * <p>Two documented psql queries answer "what's expensive" straight off the
 * {@code ai_usage} ledger (COALESCE reconciles old rows that predate purpose_label):
 * <pre>
 * -- Cost by purpose_label by day (fine label; falls back to coarse call_type):
 * SELECT date_trunc('day', created_at) AS day,
 *        COALESCE(purpose_label, call_type::text) AS purpose,
 *        SUM(est_cost_micros)/1e6 AS usd, COUNT(*) AS calls,
 *        SUM(CASE WHEN estimated THEN 1 ELSE 0 END) AS estimated_rows
 * FROM ai_usage GROUP BY 1,2 ORDER BY day DESC, usd DESC;
 *
 * -- Cost by user (who to look at):
 * SELECT user_id, SUM(est_cost_micros)/1e6 AS usd, COUNT(*) AS calls
 * FROM ai_usage WHERE created_at &gt; now() - interval '7 days'
 * GROUP BY user_id ORDER BY usd DESC NULLS LAST;
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/admin/ai-cost")
@RequiredArgsConstructor
public class AiCostController {

    private static final Duration DEFAULT_WINDOW = Duration.ofDays(30);

    private final AiUsageRepository repository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> costs(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        Instant toI = to != null ? Instant.parse(to) : Instant.now();
        Instant fromI = from != null ? Instant.parse(from) : toI.minus(DEFAULT_WINDOW);

        // Group the per-(user, callType) rows into per-user summaries.
        Map<String, PerUser> byUser = new LinkedHashMap<>();
        for (CostRow r : repository.summarize(fromI, toI)) {
            String key = r.userId() == null ? "(none)" : r.userId();
            PerUser u = byUser.computeIfAbsent(key, k -> new PerUser(r.userId()));
            u.totalCostMicros += r.totalCostMicros();
            u.costByCallType.merge(r.callType(), r.totalCostMicros(), Long::sum);
            u.callsByType.merge(r.callType(), r.callCount(), Long::sum);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        byUser.values().stream()
                .sorted(Comparator.comparingLong((PerUser u) -> u.totalCostMicros).reversed())
                .forEach(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", u.userId);
                    m.put("totalCostMicros", u.totalCostMicros);
                    m.put("costByCallType", u.costByCallType);
                    // compile calls = the expensive uploads/rebuilds this user drove.
                    m.put("compileCount", u.callsByType.getOrDefault(AiCallType.COMPILE, 0L)
                            + u.callsByType.getOrDefault(AiCallType.WEAKNESS_REBUILD, 0L));
                    out.add(m);
                });

        return ResponseEntity.ok(ApiResponse.success(out));
    }

    private static final class PerUser {
        final String userId;
        long totalCostMicros;
        final Map<AiCallType, Long> costByCallType = new LinkedHashMap<>();
        final Map<AiCallType, Long> callsByType = new LinkedHashMap<>();
        PerUser(String userId) { this.userId = userId; }
    }
}
