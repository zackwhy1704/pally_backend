package com.pally.api.centre;

import com.pally.domain.centre.CentreRegenerateService;
import com.pally.domain.centre.ContentReviewService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Thin centre content review + publishing lifecycle controller.
 * Delegates all logic to {@link ContentReviewService} and {@link CentreRegenerateService}.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}/classes/{classId}")
@RequiredArgsConstructor
public class ContentReviewController {

    private final ContentReviewService contentReviewService;
    private final CentreRegenerateService centreRegenerateService;

    /**
     * Read-only teacher preview of a generated module's content (LEARN/TEST/PROVE)
     * before assigning it. Staff+class authorized; no answer keys returned.
     */
    @GetMapping("/modules/{moduleId}/preview")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> previewModule(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String moduleId) {
        return ResponseEntity.ok(
                ApiResponse.success(contentReviewService.previewModule(userId, orgId, classId, moduleId)));
    }

    /**
     * List content items with DRAFT status for review.
     */
    @GetMapping("/content/review")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listDraftContent(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(contentReviewService.listDraftContent(userId, orgId, classId)));
    }

    /**
     * Update a content item: edit content, approve, reject, or archive.
     */
    @PatchMapping("/content/{itemId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateContentItem(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String itemId,
            @RequestBody Map<String, Object> body) {
        return ResponseEntity.ok(
                ApiResponse.success(
                        contentReviewService.updateContentItem(userId, orgId, classId, itemId, body)));
    }

    /**
     * Bulk approve all DRAFT items for this class.
     */
    @PostMapping("/content/approve-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> approveAll(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(contentReviewService.approveAll(userId, orgId, classId)));
    }

    /**
     * Regenerate LEARN + TEST content for a specific wiki page topic with optional
     * teacher guidance. Existing items are replaced with new DRAFT items.
     * Rate-limited to 3 calls per page per 24 hours.
     */
    @PostMapping("/content/{pageSlug}/regenerate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> regeneratePageContent(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String pageSlug,
            @RequestBody(required = false) Map<String, Object> body) {
        String guidance = body != null ? (String) body.get("guidance") : null;
        return ResponseEntity.ok(ApiResponse.success(
                centreRegenerateService.regeneratePageContent(userId, orgId, classId, pageSlug, guidance)));
    }

    /**
     * Class-wide exam readiness: per-concept average mastery and suggestions.
     */
    @GetMapping("/exam-readiness")
    public ResponseEntity<ApiResponse<Map<String, Object>>> examReadiness(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        return ResponseEntity.ok(
                ApiResponse.success(contentReviewService.examReadiness(userId, orgId, classId)));
    }
}
