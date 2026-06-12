package com.pally.domain.review;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.notification.MilestoneNotifier;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaEntity;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaEntity.Status;
import com.pally.infrastructure.persistence.review.ContentReviewRequestJpaRepository;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.AvatarNotFoundException;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewRequestServiceTest {

    @Mock ContentReviewRequestJpaRepository reviewRepo;
    @Mock WikiRepository wikiRepository;
    @Mock AvatarRepository avatarRepository;
    @Mock SlidingWindowRateLimiter rateLimiter;
    @Mock MilestoneNotifier milestoneNotifier;

    ReviewRequestService service;

    static final String PAGE_ID = "page-1";
    static final String AVATAR_ID = "av-1";
    static final String OWNER = "user-owner";
    static final String INTRUDER = "user-other";

    WikiPage page;

    @BeforeEach
    void setUp() {
        service = new ReviewRequestService(
                reviewRepo, wikiRepository, avatarRepository, rateLimiter, milestoneNotifier);
        ReflectionTestUtils.setField(service, "webBaseUrl", "https://apalchi.com");

        page = WikiPage.create(AVATAR_ID, "fractions", "Fractions", "A fraction is part of a whole.");
        Avatar avatar = Avatar.reconstitute(
                AVATAR_ID, OWNER, "Bolt", Subject.MATHS, CharacterType.ZAP, 1, Instant.now());

        lenient().when(wikiRepository.findById(PAGE_ID)).thenReturn(Optional.of(page));
        lenient().when(avatarRepository.findById(AVATAR_ID)).thenReturn(Optional.of(avatar));
    }

    private void allowRateLimits() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.ok());
    }

    @Test
    void create_happyPath_returnsTokenAndUrl() {
        allowRateLimits();
        when(reviewRepo.countByWikiPageIdAndStatus(PAGE_ID, Status.PENDING.name())).thenReturn(0L);

        var result = service.create(PAGE_ID, OWNER, false);

        assertThat(result.token()).isNotBlank();
        assertThat(result.url()).isEqualTo("https://apalchi.com/review/" + result.token());
        assertThat(result.expiresAt()).isAfter(Instant.now().plus(13, ChronoUnit.DAYS));
        verify(reviewRepo).save(any(ContentReviewRequestJpaEntity.class));
        verify(milestoneNotifier, never()).onParentReviewRequested(any(), any(), any());
    }

    @Test
    void create_notifyParent_callsParentNotification() {
        allowRateLimits();
        when(reviewRepo.countByWikiPageIdAndStatus(PAGE_ID, Status.PENDING.name())).thenReturn(0L);

        service.create(PAGE_ID, OWNER, true);

        verify(milestoneNotifier).onParentReviewRequested(eq(OWNER), eq("Fractions"), anyString());
    }

    @Test
    void create_nonOwner_throwsNotFound() {
        assertThatThrownBy(() -> service.create(PAGE_ID, INTRUDER, false))
                .isInstanceOf(AvatarNotFoundException.class);
        verify(reviewRepo, never()).save(any());
    }

    @Test
    void create_threePendingAlready_throws409() {
        // user limiter allowed, then page-pending check trips
        when(rateLimiter.tryAcquire(eq("review-create-user:" + OWNER), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.ok());
        when(reviewRepo.countByWikiPageIdAndStatus(PAGE_ID, Status.PENDING.name())).thenReturn(3L);

        assertThatThrownBy(() -> service.create(PAGE_ID, OWNER, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(409));
    }

    @Test
    void create_dailyCapExceeded_throws429() {
        when(rateLimiter.tryAcquire(eq("review-create-user:" + OWNER), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.deny(120));

        assertThatThrownBy(() -> service.create(PAGE_ID, OWNER, false))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(429));
    }

    @Test
    void revoke_setsStatusRevoked() {
        var req = new ContentReviewRequestJpaEntity(
                "req-1", "tok", PAGE_ID, OWNER, Instant.now(), Instant.now().plus(14, ChronoUnit.DAYS));
        when(reviewRepo.findById("req-1")).thenReturn(Optional.of(req));

        service.revoke(PAGE_ID, OWNER, "req-1");

        assertThat(req.getStatus()).isEqualTo(Status.REVOKED.name());
        verify(reviewRepo).save(req);
    }

    // ── Public view / respond ────────────────────────────────────────────────

    private ContentReviewRequestJpaEntity pendingReq() {
        return new ContentReviewRequestJpaEntity(
                "req-1", "tok-123", PAGE_ID, OWNER,
                Instant.now(), Instant.now().plus(14, ChronoUnit.DAYS));
    }

    @Test
    void viewByToken_pending_returnsViewWithNoPii() {
        when(reviewRepo.findByToken("tok-123")).thenReturn(Optional.of(pendingReq()));

        var view = service.viewByToken("tok-123");

        assertThat(view.pageTitle()).isEqualTo("Fractions");
        assertThat(view.subject()).isEqualTo(Subject.MATHS.label());
        assertThat(view.contentMarkdown()).isEqualTo("A fraction is part of a whole.");
        assertThat(view.status()).isEqualTo(Status.PENDING.name());
    }

    @Test
    void viewByToken_expiredPending_lazyFlipsToExpiredThen410() {
        var expired = new ContentReviewRequestJpaEntity(
                "req-x", "tok-exp", PAGE_ID, OWNER,
                Instant.now().minus(20, ChronoUnit.DAYS),
                Instant.now().minus(6, ChronoUnit.DAYS));
        when(reviewRepo.findByToken("tok-exp")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.viewByToken("tok-exp"))
                .isInstanceOf(ReviewRequestService.GoneException.class)
                .satisfies(e -> assertThat(((ReviewRequestService.GoneException) e).getReviewStatus())
                        .isEqualTo(Status.EXPIRED.name()));
        assertThat(expired.getStatus()).isEqualTo(Status.EXPIRED.name());
    }

    @Test
    void viewByToken_revoked_throws410WithStatus() {
        var revoked = pendingReq();
        revoked.setStatus(Status.REVOKED);
        when(reviewRepo.findByToken("tok-123")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> service.viewByToken("tok-123"))
                .isInstanceOf(ReviewRequestService.GoneException.class)
                .satisfies(e -> assertThat(((ReviewRequestService.GoneException) e).getReviewStatus())
                        .isEqualTo(Status.REVOKED.name()));
    }

    @Test
    void viewByToken_unknown_throws404() {
        when(reviewRepo.findByToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.viewByToken("nope"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(404));
    }

    @Test
    void respond_approve_setsPageVerifiedWithSourceAndExpiresSiblings() {
        var req = pendingReq();
        when(reviewRepo.findByToken("tok-123")).thenReturn(Optional.of(req));
        var sibling = new ContentReviewRequestJpaEntity(
                "req-2", "tok-2", PAGE_ID, OWNER, Instant.now(), Instant.now().plus(14, ChronoUnit.DAYS));
        when(reviewRepo.findByWikiPageIdAndStatus(PAGE_ID, Status.PENDING.name()))
                .thenReturn(List.of(req, sibling));

        service.respond("tok-123", ReviewRequestService.Verdict.APPROVE, "Ms Tan", null);

        assertThat(page.getCertainty()).isEqualTo(WikiPage.Certainty.VERIFIED);
        assertThat(page.getVerificationSource()).isEqualTo("ADULT_REVIEW");
        assertThat(page.getVerifiedBy()).isEqualTo("Ms Tan");
        assertThat(req.getStatus()).isEqualTo(Status.APPROVED.name());
        assertThat(sibling.getStatus()).isEqualTo(Status.EXPIRED.name());
        verify(milestoneNotifier).onReviewCompleted(OWNER, "Fractions", true, "Ms Tan");
    }

    @Test
    void respond_approveNoReviewerName_defaultsToAReviewer() {
        var req = pendingReq();
        when(reviewRepo.findByToken("tok-123")).thenReturn(Optional.of(req));
        when(reviewRepo.findByWikiPageIdAndStatus(PAGE_ID, Status.PENDING.name()))
                .thenReturn(List.of(req));

        service.respond("tok-123", ReviewRequestService.Verdict.APPROVE, null, null);

        assertThat(page.getVerifiedBy()).isEqualTo("a reviewer");
    }

    @Test
    void respond_flag_setsPageUncertainAndStoresNote() {
        var req = pendingReq();
        when(reviewRepo.findByToken("tok-123")).thenReturn(Optional.of(req));

        service.respond("tok-123", ReviewRequestService.Verdict.FLAG, "Dad", "This is wrong, water boils at 100C.");

        assertThat(page.getCertainty()).isEqualTo(WikiPage.Certainty.UNCERTAIN);
        assertThat(req.getStatus()).isEqualTo(Status.FLAGGED.name());
        assertThat(req.getFlagNote()).isEqualTo("This is wrong, water boils at 100C.");
        verify(milestoneNotifier).onReviewCompleted(OWNER, "Fractions", false, "Dad");
    }

    @Test
    void respond_flagWithoutNote_throws400() {
        var req = pendingReq();
        when(reviewRepo.findByToken("tok-123")).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.respond(
                "tok-123", ReviewRequestService.Verdict.FLAG, "Dad", "  "))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(400));
    }

    @Test
    void respond_secondTime_throws409() {
        var req = pendingReq();
        req.setStatus(Status.APPROVED);
        when(reviewRepo.findByToken("tok-123")).thenReturn(Optional.of(req));

        assertThatThrownBy(() -> service.respond(
                "tok-123", ReviewRequestService.Verdict.APPROVE, "X", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getHttpStatus()).isEqualTo(409));
    }

    @Test
    void list_returnsSummaries() {
        var req = new ContentReviewRequestJpaEntity(
                "req-1", "tok", PAGE_ID, OWNER, Instant.now(), Instant.now().plus(14, ChronoUnit.DAYS));
        when(reviewRepo.findByWikiPageIdOrderByCreatedAtDesc(PAGE_ID)).thenReturn(List.of(req));

        var summaries = service.list(PAGE_ID, OWNER);

        assertThat(summaries).hasSize(1);
        assertThat(summaries.get(0).id()).isEqualTo("req-1");
        assertThat(summaries.get(0).status()).isEqualTo(Status.PENDING.name());
    }
}
