package com.pally.domain.review;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.notification.MilestoneNotifier;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaEntity;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaEntity.Status;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaRepository;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Trusted-adult content review: owner-side create/revoke/list of tokenized
 * review requests, plus the public token-driven view/respond flow.
 *
 * <p>Public methods touch ZERO student PII — the public-facing view exposes
 * only the page's title, subject, markdown, and lifecycle status.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReviewRequestService {

    private static final Duration TTL = Duration.ofDays(14);
    private static final int MAX_PENDING_PER_PAGE = 3;
    private static final int MAX_CREATES_PER_DAY = 10;
    private static final long DAY_MS = Duration.ofDays(1).toMillis();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ContentReviewRequestJpaRepository reviewRepo;
    private final WikiRepository wikiRepository;
    private final AvatarRepository avatarRepository;
    private final SlidingWindowRateLimiter rateLimiter;
    private final MilestoneNotifier milestoneNotifier;

    @Value("${app.web-base-url:https://apalchi.com}")
    private String webBaseUrl;

    // ── Owner-facing results ─────────────────────────────────────────────────

    public record CreateResult(String token, String url, Instant expiresAt) {}

    public record RequestSummary(String id, String status, String reviewerName,
                                 Instant createdAt, Instant reviewedAt, Instant expiresAt) {}

    public record PublicView(String pageTitle, String subject, String contentMarkdown,
                             String status, Instant createdAt) {}

    // ── Owner: create ────────────────────────────────────────────────────────

    /**
     * Creates a tokenized review request for a wiki page the caller owns.
     *
     * @throws AvatarNotFoundException if the page's avatar isn't owned by the user (404)
     * @throws BusinessException 409 when 3 PENDING requests already exist for the page
     * @throws BusinessException 429 when the user exceeds 10 creates/day
     */
    @Transactional
    public CreateResult create(String pageId, String userId, boolean notifyParent) {
        WikiPage page = requireOwnedPage(pageId, userId);

        // Per-user daily create cap (mutating check-and-take).
        var userRl = rateLimiter.tryAcquire(
                "review-create-user:" + userId, MAX_CREATES_PER_DAY, DAY_MS);
        if (!userRl.allowed()) {
            throw new BusinessException(
                    "You've requested too many reviews today. Try again in "
                            + userRl.retryAfterSeconds() + "s.", 429);
        }

        // Per-page PENDING cap. A separate limiter key bounds rapid re-requests
        // even when older PENDINGs are still under the count cap.
        long pending = reviewRepo.countByWikiPageIdAndStatus(pageId, Status.PENDING.name());
        if (pending >= MAX_PENDING_PER_PAGE) {
            throw new BusinessException(
                    "This page already has the maximum number of open review requests.", 409);
        }
        var pageRl = rateLimiter.tryAcquire(
                "review-create-page:" + pageId, MAX_CREATES_PER_DAY, DAY_MS);
        if (!pageRl.allowed()) {
            throw new BusinessException(
                    "Too many review requests for this page. Try again in "
                            + pageRl.retryAfterSeconds() + "s.", 429);
        }

        Instant now = Instant.now();
        String token = generateToken();
        ContentReviewRequestJpaEntity entity = new ContentReviewRequestJpaEntity(
                IdGenerator.newId(), token, pageId, userId, now, now.plus(TTL));
        reviewRepo.save(entity);

        String url = webBaseUrl + "/review/" + token;
        log.info("[Review] Created request id={} page={} by user={} notifyParent={}",
                entity.getId(), pageId, userId, notifyParent);

        if (notifyParent) {
            try {
                milestoneNotifier.onParentReviewRequested(userId, page.getTitle(), url);
            } catch (Exception e) {
                log.warn("[Review] Parent notify failed (non-fatal): {}", e.getMessage());
            }
        }

        return new CreateResult(token, url, entity.getExpiresAt());
    }

    // ── Owner: revoke ────────────────────────────────────────────────────────

    @Transactional
    public void revoke(String pageId, String userId, String requestId) {
        requireOwnedPage(pageId, userId);
        ContentReviewRequestJpaEntity req = reviewRepo.findById(requestId)
                .filter(r -> r.getWikiPageId().equals(pageId))
                .orElseThrow(() -> new BusinessException("Review request not found", 404));
        req.setStatus(Status.REVOKED);
        req.setReviewedAt(Instant.now());
        reviewRepo.save(req);
        log.info("[Review] Revoked request id={} page={} by user={}", requestId, pageId, userId);
    }

    // ── Owner: list ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<RequestSummary> list(String pageId, String userId) {
        requireOwnedPage(pageId, userId);
        return reviewRepo.findByWikiPageIdOrderByCreatedAtDesc(pageId).stream()
                .map(r -> new RequestSummary(
                        r.getId(), r.getStatus(), r.getReviewerName(),
                        r.getCreatedAt(), r.getReviewedAt(), r.getExpiresAt()))
                .toList();
    }

    // ── Public: view by token (no PII) ───────────────────────────────────────

    /**
     * Public token view. Lazily expires a PENDING-but-past-TTL request.
     *
     * @throws BusinessException 404 if the token is unknown
     * @throws BusinessException 410 (gone) if the request is no longer PENDING
     */
    @Transactional
    public PublicView viewByToken(String token) {
        ContentReviewRequestJpaEntity req = reviewRepo.findByToken(token)
                .orElseThrow(() -> new BusinessException("Review link not found", 404));

        Instant now = Instant.now();
        if (req.isPending() && req.isExpired(now)) {
            req.setStatus(Status.EXPIRED);
            req.setReviewedAt(now);
            reviewRepo.save(req);
        }

        if (!req.isPending()) {
            throw new GoneException(req.getStatus());
        }

        // Resolve the page directly by id — the public view must not leak avatar/user.
        WikiPage page = loadPageById(req.getWikiPageId());
        if (page == null) {
            throw new BusinessException("The reviewed page no longer exists", 404);
        }
        String subject = subjectLabel(page.getAvatarId());
        return new PublicView(page.getTitle(), subject, page.getContent(),
                req.getStatus(), req.getCreatedAt());
    }

    // ── Public: respond by token ─────────────────────────────────────────────

    public enum Verdict { APPROVE, FLAG }

    /**
     * Public single-shot respond. Approves or flags the page, resolves all
     * sibling PENDING requests, and notifies the student.
     *
     * @throws BusinessException 404 unknown token
     * @throws BusinessException 409 if the request is not (still) PENDING
     * @throws BusinessException 400 if FLAG has no note (or note > 500 chars)
     */
    @Transactional
    public void respond(String token, Verdict verdict, String reviewerName, String note) {
        ContentReviewRequestJpaEntity req = reviewRepo.findByToken(token)
                .orElseThrow(() -> new BusinessException("Review link not found", 404));

        Instant now = Instant.now();
        if (req.isPending() && req.isExpired(now)) {
            req.setStatus(Status.EXPIRED);
            req.setReviewedAt(now);
            reviewRepo.save(req);
        }
        if (!req.isPending()) {
            throw new BusinessException("This review has already been completed.", 409);
        }

        if (verdict == Verdict.FLAG) {
            if (note == null || note.isBlank()) {
                throw new BusinessException("A note is required when flagging.", 400);
            }
            if (note.length() > 500) {
                throw new BusinessException("Note must be 500 characters or fewer.", 400);
            }
        }

        WikiPage page = loadPageById(req.getWikiPageId());
        if (page == null) {
            throw new BusinessException("The reviewed page no longer exists", 404);
        }

        if (reviewerName != null) {
            reviewerName = reviewerName.strip();
            if (reviewerName.length() > 80) reviewerName = reviewerName.substring(0, 80);
            if (reviewerName.isBlank()) reviewerName = null;
        }

        if (verdict == Verdict.APPROVE) {
            page.markAdultReviewApproved(reviewerName);
            wikiRepository.save(page);

            req.setStatus(Status.APPROVED);
            req.setReviewerName(reviewerName);
            req.setReviewedAt(now);
            reviewRepo.save(req);

            // All other open requests for the same page are now moot.
            expireSiblingPending(req.getWikiPageId(), req.getId(), now);

            notifyStudent(req.getRequesterId(), page.getTitle(), true, reviewerName);
            log.info("[Review] APPROVED token-page={} by reviewer={}",
                    req.getWikiPageId(), reviewerName);
        } else {
            page.markAdultReviewFlagged();
            wikiRepository.save(page);

            req.setStatus(Status.FLAGGED);
            req.setReviewerName(reviewerName);
            req.setFlagNote(note);
            req.setReviewedAt(now);
            reviewRepo.save(req);

            notifyStudent(req.getRequesterId(), page.getTitle(), false, reviewerName);
            log.info("[Review] FLAGGED token-page={} by reviewer={}",
                    req.getWikiPageId(), reviewerName);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void notifyStudent(String studentId, String pageTitle, boolean approved, String reviewerName) {
        try {
            milestoneNotifier.onReviewCompleted(studentId, pageTitle, approved, reviewerName);
        } catch (Exception e) {
            log.warn("[Review] Student notify failed (non-fatal): {}", e.getMessage());
        }
    }

    private void expireSiblingPending(String pageId, String keepRequestId, Instant now) {
        for (var sibling : reviewRepo.findByWikiPageIdAndStatus(pageId, Status.PENDING.name())) {
            if (sibling.getId().equals(keepRequestId)) continue;
            sibling.setStatus(Status.EXPIRED);
            sibling.setReviewedAt(now);
            reviewRepo.save(sibling);
        }
    }

    /** Owner ownership assertion — mirrors KnowledgeController's avatar-ownership check. */
    private WikiPage requireOwnedPage(String pageId, String userId) {
        WikiPage page = loadPageById(pageId);
        if (page == null) {
            throw new BusinessException("Wiki page not found", 404);
        }
        avatarRepository.findById(page.getAvatarId())
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(page.getAvatarId()));
        return page;
    }

    private WikiPage loadPageById(String pageId) {
        return wikiRepository.findById(pageId).orElse(null);
    }

    private String subjectLabel(String avatarId) {
        return avatarRepository.findById(avatarId)
                .map(Avatar::getSubject)
                .map(s -> s != null ? s.label() : "General")
                .orElse("General");
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Signals an HTTP 410 Gone for a public review link that is no longer PENDING. */
    public static class GoneException extends BusinessException {
        private final String reviewStatus;
        public GoneException(String reviewStatus) {
            super("This review link is no longer active (" + reviewStatus + ").", 410);
            this.reviewStatus = reviewStatus;
        }
        public String getReviewStatus() { return reviewStatus; }
    }
}
