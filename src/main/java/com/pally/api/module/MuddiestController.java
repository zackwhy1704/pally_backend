package com.pally.api.module;

import com.pally.domain.module.MuddiestService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Anonymous "muddiest point" voting endpoints. The class aggregate exposes
 * concept counts only — no user identifiers are ever returned.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class MuddiestController {

    private final MuddiestService muddiestService;

    /**
     * Record (or change) the caller's muddiest-point vote for a module.
     * Body: {"conceptId": "..."}. One vote per student per module (UPSERT).
     */
    @PostMapping("/api/v1/modules/{moduleId}/muddiest")
    public ResponseEntity<ApiResponse<Map<String, Object>>> vote(
            @AuthenticationPrincipal String userId,
            @PathVariable String moduleId,
            @RequestBody Map<String, String> body) {
        String conceptId = body != null ? body.get("conceptId") : null;
        Map<String, Object> result = muddiestService.vote(moduleId, userId, conceptId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Class aggregate for a module: [{conceptId, conceptLabel, count}]. No user
     * ids. {@code moduleId} narrows the aggregate to a single module.
     */
    @GetMapping("/api/v1/classes/{classId}/muddiest")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> aggregate(
            @AuthenticationPrincipal String userId,
            @PathVariable String classId,
            @RequestParam String moduleId) {
        List<Map<String, Object>> agg = muddiestService.aggregate(moduleId);
        return ResponseEntity.ok(ApiResponse.success(agg));
    }
}
