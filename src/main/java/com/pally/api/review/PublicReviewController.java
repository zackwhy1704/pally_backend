package com.pally.api.review;

import com.pally.domain.review.ReviewRequestService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;

/**
 * PUBLIC (no-auth) tokenized review flow for trusted adults.
 *
 * <p>Routes under {@code /api/v1/review/**} are in the SecurityConfig
 * permitAll list. Responses expose ZERO student PII — only page title,
 * subject, markdown, and lifecycle status. Single-shot: once a request
 * leaves PENDING (approved/flagged/expired/revoked) it is gone (410).
 */
@RestController
@RequestMapping("/api/v1/review")
@RequiredArgsConstructor
public class PublicReviewController {

    private static final int RESPOND_IP_LIMIT = 20;
    private static final long HOUR_MS = Duration.ofHours(1).toMillis();

    private final ReviewRequestService reviewRequestService;
    private final SlidingWindowRateLimiter rateLimiter;

    public record PublicViewResponse(String pageTitle, String subject,
                                     String contentMarkdown, String status, Instant createdAt) {}

    @GetMapping("/{token}")
    public ResponseEntity<ApiResponse<PublicViewResponse>> view(@PathVariable String token) {
        ReviewRequestService.PublicView v = reviewRequestService.viewByToken(token);
        return ResponseEntity.ok(ApiResponse.success(new PublicViewResponse(
                v.pageTitle(), v.subject(), v.contentMarkdown(), v.status(), v.createdAt())));
    }

    public record RespondRequest(String verdict, String reviewerName, String note) {}

    @PostMapping("/{token}/respond")
    public ResponseEntity<ApiResponse<Object>> respond(
            @PathVariable String token,
            @RequestBody RespondRequest body,
            HttpServletRequest request
    ) {
        String ip = clientIp(request);
        var rl = rateLimiter.tryAcquire("review-respond:" + ip, RESPOND_IP_LIMIT, HOUR_MS);
        if (!rl.allowed()) {
            throw new BusinessException(
                    "Too many responses. Try again in " + rl.retryAfterSeconds() + "s.", 429);
        }

        if (body == null || body.verdict() == null) {
            throw new BusinessException("verdict is required (APPROVE or FLAG).", 400);
        }
        ReviewRequestService.Verdict verdict = switch (body.verdict().trim().toUpperCase()) {
            case "APPROVE" -> ReviewRequestService.Verdict.APPROVE;
            case "FLAG" -> ReviewRequestService.Verdict.FLAG;
            default -> throw new BusinessException("verdict must be APPROVE or FLAG.", 400);
        };

        reviewRequestService.respond(token, verdict, body.reviewerName(), body.note());
        return ResponseEntity.ok(ApiResponse.success(java.util.Map.of(
                "status", verdict == ReviewRequestService.Verdict.APPROVE ? "APPROVED" : "FLAGGED")));
    }

    private String clientIp(HttpServletRequest req) {
        String fwd = req.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) return fwd.split(",")[0].trim();
        return req.getRemoteAddr();
    }
}
