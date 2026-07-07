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
