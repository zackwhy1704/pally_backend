package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.centre.CentreAnalyticsService;
import com.pally.shared.response.ApiResponse;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Centre analytics endpoints for the admin web dashboard.
 *
 * <p>Authorization: most routes require the caller to be an active org owner
 * OR staff member — enforced via {@link CentreAccessService#ensureStaff}.
 * The {@code /cost-summary} route is owner-only (financial data) and uses
 * {@link CentreAccessService#ensureOwner}.
 *
 * <p>This controller is a thin routing shell: it enforces access, decodes
 * path variables, then delegates all query and aggregation logic to
 * {@link CentreAnalyticsService}.
 */
@RestController
@RequestMapping("/api/v1/centre")
@RequiredArgsConstructor
@Slf4j
public class CentreAnalyticsController {

    private final CentreAccessService    accessService;
    private final CentreAnalyticsService analyticsService;
    private final MeterRegistry          meterRegistry;

    // ── GET /organizations/{orgId}/overview?cohort= ────────────────────────

    @GetMapping("/organizations/{orgId}/overview")
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort) {

        accessService.ensureStaff(userId, orgId);
        Map<String, Object> result = analyticsService.overview(orgId, CentreAnalyticsService.blankToNull(cohort));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── GET /organizations/{orgId}/cohorts ────────────────────────────────

    @GetMapping("/organizations/{orgId}/cohorts")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> classes(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {

        accessService.ensureStaff(userId, orgId);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.cohorts(orgId)));
    }

    // ── GET /organizations/{orgId}/classes/{cohort}/heatmap ────────────────

    @GetMapping("/organizations/{orgId}/classes/{cohort}/heatmap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> heatmap(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String cohort) {

        accessService.ensureStaff(userId, orgId);
        String decodedCohort = URLDecoder.decode(cohort, StandardCharsets.UTF_8);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.heatmap(orgId, decodedCohort)));
    }

    // ── GET /organizations/{orgId}/students/{studentUserId} ───────────────

    @GetMapping("/organizations/{orgId}/students/{studentUserId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> studentProgress(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String studentUserId) {

        accessService.ensureStaff(userId, orgId);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.studentProgress(orgId, studentUserId)));
    }

    // ── GET /organizations/{orgId}/classes/{classId}/modules ───────────────

    @GetMapping("/organizations/{orgId}/classes/{classId}/modules")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> classModules(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {

        accessService.ensureStaff(userId, orgId);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.classModules(classId)));
    }

    // ── GET /organizations/{orgId}/classes/{classId}/concept-mastery ──────

    @GetMapping("/organizations/{orgId}/classes/{classId}/concept-mastery")
    public ResponseEntity<ApiResponse<Map<String, Object>>> conceptMastery(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {

        accessService.ensureStaff(userId, orgId);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.conceptMastery(classId)));
    }

    // ── GET /organizations/{orgId}/cost-summary ───────────────────────────

    /**
     * Returns an in-process cost summary by aggregating the
     * {@code pally.user.ai_cost} Micrometer counters. These counters are
     * incremented by {@code ClaudeMetrics#recordUserCost} on every AI call.
     *
     * <p>NOTE: counters reset on deploy. For durable cost history, scrape
     * Prometheus and query there. This endpoint is best-effort for the
     * admin dashboard's "since last deploy" view.
     */
    @GetMapping("/organizations/{orgId}/cost-summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> costSummary(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {

        accessService.ensureOwner(userId, orgId);
        return ResponseEntity.ok(ApiResponse.success(analyticsService.costSummary(orgId, meterRegistry)));
    }
}
