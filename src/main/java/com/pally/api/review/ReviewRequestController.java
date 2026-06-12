package com.pally.api.review;

import com.pally.domain.review.ReviewRequestService;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Authenticated, owner-only review-request management for a wiki page.
 *
 * <p>{@code /api/v1/wiki-pages/{pageId}/review-request} — create/revoke/list.
 * The public tokenized view/respond endpoints live in {@link PublicReviewController}.
 */
@RestController
@RequestMapping("/api/v1/wiki-pages/{pageId}")
@RequiredArgsConstructor
public class ReviewRequestController {

    private final ReviewRequestService reviewRequestService;

    public record CreateRequest(boolean notifyParent) {}

    public record CreateResponse(String token, String url, Instant expiresAt) {}

    @PostMapping("/review-request")
    public ResponseEntity<ApiResponse<CreateResponse>> create(
            @AuthenticationPrincipal String userId,
            @PathVariable String pageId,
            @RequestBody(required = false) CreateRequest body
    ) {
        boolean notifyParent = body != null && body.notifyParent();
        ReviewRequestService.CreateResult result =
                reviewRequestService.create(pageId, userId, notifyParent);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.created(
                new CreateResponse(result.token(), result.url(), result.expiresAt())));
    }

    @DeleteMapping("/review-request/{id}")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal String userId,
            @PathVariable String pageId,
            @PathVariable String id
    ) {
        reviewRequestService.revoke(pageId, userId, id);
        return ResponseEntity.noContent().build();
    }

    public record RequestSummaryResponse(String id, String status, String reviewerName,
                                         Instant createdAt, Instant reviewedAt, Instant expiresAt) {}

    @GetMapping("/review-requests")
    public ResponseEntity<ApiResponse<List<RequestSummaryResponse>>> list(
            @AuthenticationPrincipal String userId,
            @PathVariable String pageId
    ) {
        List<RequestSummaryResponse> responses =
                reviewRequestService.list(pageId, userId).stream()
                        .map(s -> new RequestSummaryResponse(
                                s.id(), s.status(), s.reviewerName(),
                                s.createdAt(), s.reviewedAt(), s.expiresAt()))
                        .toList();
        return ResponseEntity.ok(ApiResponse.success(responses));
    }
}
