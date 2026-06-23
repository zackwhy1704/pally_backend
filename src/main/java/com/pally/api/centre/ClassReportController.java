package com.pally.api.centre;

import com.pally.domain.centre.ClassReportService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Thin controller for the AI class narrative report.
 * Delegates all logic to {@link ClassReportService}.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}/report")
@RequiredArgsConstructor
public class ClassReportController {

    private final ClassReportService classReportService;

    /**
     * Returns the AI class report state — {@code ready} / {@code generating} /
     * {@code failed}. Returns fast (never blocks on Claude); the client polls
     * while {@code generating}. {@code ?refresh=true} forces regeneration after a
     * failure. Cached for 60 minutes. Auth: staff or owner of the org.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReport(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(
                ApiResponse.success(classReportService.getReport(userId, orgId, classId, refresh)));
    }
}
