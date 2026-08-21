package com.pally.api.admin;

import com.pally.domain.metrics.CohortMetricsService;
import com.pally.domain.metrics.dto.CohortMetrics;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cohort learning-outcome metrics. Read-only.
 *
 * <p>Admin-scoped by route: SecurityConfig gates {@code /api/v1/admin/**} with
 * {@code hasRole("ADMIN")}. This aggregates across ALL students, so it must never sit
 * on a student- or centre-reachable path — unlike the per-module mastery audit, which
 * is self-scoped by principal.
 */
@RestController
@RequestMapping("/api/v1/admin/cohort-metrics")
@RequiredArgsConstructor
public class CohortMetricsController {

    private final CohortMetricsService cohortMetricsService;

    @GetMapping
    public ResponseEntity<ApiResponse<CohortMetrics>> metrics(
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String subject) {
        return ResponseEntity.ok(ApiResponse.success(cohortMetricsService.compute(level, subject)));
    }
}
