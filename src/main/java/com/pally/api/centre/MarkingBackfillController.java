package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.marking.MarkingBackfillService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Owner-triggered, idempotent backfill of a centre's existing raw marking
 * references into the compiled marking-wiki. Safe to re-run — already-ingested
 * references are skipped.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/marking-backfill")
@RequiredArgsConstructor
@Slf4j
public class MarkingBackfillController {

    private final CentreAccessService accessService;
    private final MarkingBackfillService backfillService;

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfill(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        accessService.ensureOwner(userId, orgId);
        int filesIngested = backfillService.backfillOrg(orgId);
        log.info("[MarkingBackfill] org={} owner={} filesIngested={}", orgId, userId, filesIngested);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "orgId", orgId,
                "filesIngested", filesIngested)));
    }
}
