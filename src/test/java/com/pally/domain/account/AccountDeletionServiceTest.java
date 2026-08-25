package com.pally.domain.account;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.subscription.SubscriptionRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.auth.AuthChallengeService;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.CentreNotEmptyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountDeletionService} — the deletion REQUEST transition.
 * Pins the LOCKED invariants: re-auth is mandatory (bearer alone can't delete),
 * org-owner/parent blocks, IAP is flagged without a Stripe call, and a child's
 * parent is notified.
 */
@ExtendWith(MockitoExtension.class)
class AccountDeletionServiceTest {

    @Mock UserRepository userRepo;
    @Mock SubscriptionRepository subscriptionRepo;
    @Mock CentreAccessService centreAccess;
    @Mock AuthChallengeService authChallenge;
    @Mock StripeService stripeService;
    @Mock EmailService emailService;
    @Mock SlidingWindowRateLimiter rateLimiter;
    @Mock org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder passwordEncoder;

    AccountDeletionService service;

    private static final String USER = "user-1";
    private static final int GRACE_DAYS = 14;

    @BeforeEach
    void setUp() {
        service = new AccountDeletionService(userRepo, subscriptionRepo, centreAccess,
                authChallenge, stripeService, emailService, rateLimiter, passwordEncoder,
                GRACE_DAYS, "https://apalchi.com");
    }

    private User user(String email) {
        User u = new User();
        u.setId(USER);
        u.setEmail(email);
        return u;
    }

    private void allowRate() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.ok());
    }

    private void passwordAccount(boolean matches) {
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.of("$2a$hash"));
        when(passwordEncoder.matches(anyString(), eq("$2a$hash"))).thenReturn(matches);
    }

    private void noSubscription() {
        when(subscriptionRepo.findById(USER)).thenReturn(Optional.empty());
    }

    private SubscriptionRepository.Subscription sub(String status, String stripeSubId) {
        return new SubscriptionRepository.Subscription(
                USER, "cus_1", stripeSubId, "monthly", status,
                null, false, null, Instant.now(), Instant.now(), null);
    }

    // ── Re-auth is mandatory ────────────────────────────────────────────────────

    @Test
    void requestDeletion_passwordAccount_wrongPassword_throws401_andDoesNotTransition() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        allowRate();
        passwordAccount(false);

        assertThatThrownBy(() -> service.requestDeletion(USER, "wrong", null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 401);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }

    @Test
    void requestDeletion_passwordAccount_nullPassword_bearerOnly_throws401() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        allowRate();
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.of("$2a$hash"));

        assertThatThrownBy(() -> service.requestDeletion(USER, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 401);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }

    @Test
    void requestDeletion_passwordless_invalidCode_throws401() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        allowRate();
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.empty());
        when(authChallenge.consumeDeleteCode(USER, "000000")).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeletion(USER, null, "000000"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 401);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }

    @Test
    void requestDeletion_passwordless_validCode_transitions() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user(null))); // no email
        allowRate();
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.empty());
        when(authChallenge.consumeDeleteCode(USER, "123456")).thenReturn(true);
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(true);
        when(userRepo.countByParentId(USER)).thenReturn(0);
        noSubscription();

        var res = service.requestDeletion(USER, null, "123456");

        assertThat(res.needsManualCancellation()).isFalse();
        verify(userRepo).markDeletionPending(eq(USER), any(Instant.class));
    }

    @Test
    void requestDeletion_rateLimited_throws429_beforeReauth() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.deny(60));

        assertThatThrownBy(() -> service.requestDeletion(USER, "pw", null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 429);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }

    // ── Guards ──────────────────────────────────────────────────────────────────

    @Test
    void requestDeletion_orgOwnerNonEmpty_throws409CentreNotEmpty_noTransition() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        allowRate();
        passwordAccount(true);
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(false);

        assertThatThrownBy(() -> service.requestDeletion(USER, "pw", null))
                .isInstanceOf(CentreNotEmptyException.class);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }

    @Test
    void requestDeletion_parentWithChildren_throws409_noTransition() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        allowRate();
        passwordAccount(true);
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(true);
        when(userRepo.countByParentId(USER)).thenReturn(2);

        assertThatThrownBy(() -> service.requestDeletion(USER, "pw", null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 409);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }

    // ── Subscription: IAP flagged without a Stripe call ─────────────────────────

    @Test
    void requestDeletion_iapSub_flagsManualCancellation_andNeverCallsStripe() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user(null)));
        allowRate();
        passwordAccount(true);
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(true);
        when(userRepo.countByParentId(USER)).thenReturn(0);
        when(subscriptionRepo.findById(USER))
                .thenReturn(Optional.of(sub("active", null))); // active, NO stripe sub id

        var res = service.requestDeletion(USER, "pw", null);

        assertThat(res.needsManualCancellation()).isTrue();
        verify(stripeService, never()).cancelSubscriptionForUser(anyString());
        verify(userRepo).markDeletionPending(eq(USER), any(Instant.class));
    }

    @Test
    void requestDeletion_stripeSub_cancelsServerSide_noManualFlag() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user(null)));
        allowRate();
        passwordAccount(true);
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(true);
        when(userRepo.countByParentId(USER)).thenReturn(0);
        when(subscriptionRepo.findById(USER))
                .thenReturn(Optional.of(sub("active", "sub_123")));

        var res = service.requestDeletion(USER, "pw", null);

        assertThat(res.needsManualCancellation()).isFalse();
        verify(stripeService).cancelSubscriptionForUser("sub_123");
    }

    // ── Child → parent notification ─────────────────────────────────────────────

    @Test
    void requestDeletion_childAccount_notifiesParentByEmail() {
        User child = user(null);
        child.setParentId("parent-1");
        child.setDisplayName("Kiddo");
        User parent = new User();
        parent.setId("parent-1");
        parent.setEmail("mum@example.com");

        when(userRepo.findById(USER)).thenReturn(Optional.of(child));
        allowRate();
        passwordAccount(true);
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(true);
        when(userRepo.countByParentId(USER)).thenReturn(0);
        noSubscription();
        when(authChallenge.createRestoreToken(USER, GRACE_DAYS)).thenReturn("tok");
        when(userRepo.findById("parent-1")).thenReturn(Optional.of(parent));

        service.requestDeletion(USER, "pw", null);

        verify(emailService).sendHtml(eq("mum@example.com"), anyString(), anyString());
    }

    // ── send-code ────────────────────────────────────────────────────────────────

    @Test
    void sendDeleteCodeIfPasswordless_passwordAccount_isNoOp() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.of("$2a$hash"));

        service.sendDeleteCodeIfPasswordless(USER);

        verify(authChallenge, never()).createDeleteCode(anyString());
        verify(emailService, never()).sendHtml(anyString(), anyString(), anyString());
    }

    @Test
    void sendDeleteCodeIfPasswordless_passwordless_emailsCode() {
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com")));
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.empty());
        when(authChallenge.createDeleteCode(USER)).thenReturn("654321");

        service.sendDeleteCodeIfPasswordless(USER);

        verify(emailService).sendHtml(eq("a@b.com"), anyString(), anyString());
    }

    // ── restore ──────────────────────────────────────────────────────────────

    private User pendingUser() {
        User u = user("a@b.com");
        u.setAccountStatus(ConsentGuard.STATUS_DELETION_PENDING);
        return u;
    }

    @Test
    void restore_validToken_clearsPending() {
        when(authChallenge.consumeRestoreToken("tok")).thenReturn(Optional.of(USER));
        when(userRepo.findById(USER)).thenReturn(Optional.of(pendingUser()));

        var res = service.restore("tok", null, null);

        assertThat(res.restored()).isTrue();
        verify(userRepo).clearDeletionPending(USER);
    }

    @Test
    void restore_invalidToken_throws400() {
        when(authChallenge.consumeRestoreToken("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore("bad", null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);

        verify(userRepo, never()).clearDeletionPending(anyString());
    }

    @Test
    void restore_accountNotPending_isIdempotentNoOp() {
        when(authChallenge.consumeRestoreToken("tok")).thenReturn(Optional.of(USER));
        when(userRepo.findById(USER)).thenReturn(Optional.of(user("a@b.com"))); // ACTIVE

        var res = service.restore("tok", null, null);

        assertThat(res.restored()).isFalse();
        verify(userRepo, never()).clearDeletionPending(anyString());
    }

    @Test
    void restore_passwordPath_validPassword_clearsPending() {
        allowRate();
        when(userRepo.findByEmail("a@b.com")).thenReturn(Optional.of(pendingUser()));
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.of("$2a$hash"));
        when(passwordEncoder.matches("pw", "$2a$hash")).thenReturn(true);
        when(userRepo.findById(USER)).thenReturn(Optional.of(pendingUser()));

        var res = service.restore(null, "a@b.com", "pw");

        assertThat(res.restored()).isTrue();
        verify(userRepo).clearDeletionPending(USER);
    }

    @Test
    void restore_passwordPath_wrongPassword_throws401() {
        allowRate();
        when(userRepo.findByEmail("a@b.com")).thenReturn(Optional.of(pendingUser()));
        when(userRepo.getPasswordHash(USER)).thenReturn(Optional.of("$2a$hash"));
        when(passwordEncoder.matches("bad", "$2a$hash")).thenReturn(false);

        assertThatThrownBy(() -> service.restore(null, "a@b.com", "bad"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 401);

        verify(userRepo, never()).clearDeletionPending(anyString());
    }

    @Test
    void restore_noCredentials_throws400() {
        assertThatThrownBy(() -> service.restore(null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);
    }

    // ── public delete-by-email (Phase 2) ────────────────────────────────────────

    @Test
    void requestByEmail_unknownEmail_mintsNothing_butDoesNotThrow() {
        allowRate();
        when(userRepo.findByEmail("x@y.com")).thenReturn(Optional.empty());

        service.requestDeletionByEmail("x@y.com"); // non-enumerating: silent

        verify(authChallenge, never()).createDeleteConfirmToken(anyString());
    }

    @Test
    void requestByEmail_knownEmail_mintsConfirmToken() {
        allowRate();
        when(userRepo.findByEmail("x@y.com")).thenReturn(Optional.of(user("x@y.com")));
        when(authChallenge.createDeleteConfirmToken(USER)).thenReturn("tok");

        service.requestDeletionByEmail("x@y.com");

        verify(authChallenge).createDeleteConfirmToken(USER);
    }

    @Test
    void requestByEmail_rateLimited_throws429() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyLong()))
                .thenReturn(SlidingWindowRateLimiter.Result.deny(60));

        assertThatThrownBy(() -> service.requestDeletionByEmail("x@y.com"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 429);
    }

    @Test
    void confirmByToken_validToken_runsSamePipeline_transitions() {
        when(authChallenge.consumeDeleteConfirmToken("tok")).thenReturn(Optional.of(USER));
        when(userRepo.findById(USER)).thenReturn(Optional.of(user(null)));
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(true);
        when(userRepo.countByParentId(USER)).thenReturn(0);
        noSubscription();

        service.confirmDeletionByToken("tok");

        verify(userRepo).markDeletionPending(eq(USER), any(Instant.class));
    }

    @Test
    void confirmByToken_invalidToken_throws400() {
        when(authChallenge.consumeDeleteConfirmToken("bad")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmDeletionByToken("bad"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 400);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }

    @Test
    void confirmByToken_orgOwner_throws409CentreNotEmpty_atConfirmTime() {
        // Rider 1: the org-owner block fires at CONFIRM time (rendered on the page), not
        // discovered at purge.
        when(authChallenge.consumeDeleteConfirmToken("tok")).thenReturn(Optional.of(USER));
        when(userRepo.findById(USER)).thenReturn(Optional.of(user(null)));
        when(centreAccess.isOwnedCentreEmpty(USER)).thenReturn(false);

        assertThatThrownBy(() -> service.confirmDeletionByToken("tok"))
                .isInstanceOf(CentreNotEmptyException.class);

        verify(userRepo, never()).markDeletionPending(anyString(), any());
    }
}
