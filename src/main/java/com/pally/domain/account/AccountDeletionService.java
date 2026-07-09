package com.pally.domain.account;

import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.subscription.SubscriptionRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.auth.AuthChallengeService;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.infrastructure.stripe.StripeService;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.exception.CentreNotEmptyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;

/**
 * ACCOUNT DELETION Phase 1 — user-initiated deletion REQUEST (build order step 2).
 *
 * <p>Moves the account into a {@code DELETION_PENDING} grace window rather than
 * purging immediately; the {@code DeletionPurgeReaper} does the irreversible purge
 * after the grace elapses, and logging in / the emailed restore link during grace
 * cancels it. This class owns ONLY the request transition — never the purge.
 *
 * <p>Hard invariants enforced here (LOCKED policy):
 * <ul>
 *   <li><b>Re-auth is mandatory.</b> A bearer token alone can never initiate
 *       deletion: a password account must re-supply its password; a passwordless
 *       (social) account must supply a {@link AuthChallengeService#PURPOSE_DELETE}
 *       code previously emailed to it. Re-auth is rate-limited.</li>
 *   <li><b>Org owner block-unless-empty.</b> An owner of a non-empty centre is
 *       rejected 409 {@code CENTRE_NOT_EMPTY} (re-checked at purge time too).</li>
 *   <li><b>Parent with children blocked.</b> Mirrors the purge engine so a parent is
 *       not left PENDING then aborted forever; they must unlink children first.</li>
 *   <li><b>The epoch bump is the wall.</b> {@code markDeletionPending} bumps
 *       session_epoch, logging the account out everywhere immediately.</li>
 *   <li><b>Emails are best-effort</b> and never block the state change.</li>
 * </ul>
 */
@Service
@Slf4j
public class AccountDeletionService {

    /// Sub statuses that count as a live paid subscription for cancel/IAP purposes.
    private static final Set<String> ACTIVE_SUB_STATUSES = Set.of("active", "trialing", "past_due");
    /// Re-auth attempts allowed per user per window (destructive action → tight).
    private static final int REAUTH_LIMIT = 5;
    private static final long REAUTH_WINDOW_MS = 15 * 60 * 1000L;

    private final UserRepository userRepo;
    private final SubscriptionRepository subscriptionRepo;
    private final CentreAccessService centreAccess;
    private final AuthChallengeService authChallenge;
    private final StripeService stripeService;
    private final EmailService emailService;
    private final SlidingWindowRateLimiter rateLimiter;
    private final BCryptPasswordEncoder passwordEncoder;
    private final int graceDays;
    private final String webBaseUrl;

    public AccountDeletionService(UserRepository userRepo,
                                  SubscriptionRepository subscriptionRepo,
                                  CentreAccessService centreAccess,
                                  AuthChallengeService authChallenge,
                                  StripeService stripeService,
                                  EmailService emailService,
                                  SlidingWindowRateLimiter rateLimiter,
                                  BCryptPasswordEncoder passwordEncoder,
                                  @Value("${account.deletion.grace-days:14}") int graceDays,
                                  @Value("${app.web-base-url:https://apalchi.com}") String webBaseUrl) {
        this.userRepo = userRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.centreAccess = centreAccess;
        this.authChallenge = authChallenge;
        this.stripeService = stripeService;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
        this.passwordEncoder = passwordEncoder;
        this.graceDays = graceDays;
        this.webBaseUrl = webBaseUrl;
    }

    /** Result of a successful deletion request — what the client needs to render. */
    public record DeletionRequestResult(Instant graceEndsAt, boolean needsManualCancellation) {}

    /**
     * Emails a passwordless (social) account a 6-digit re-auth code so it can confirm
     * a deletion request. A no-op (silent) for password accounts — they re-auth with
     * their password directly — and for accounts with no email on file.
     */
    public void sendDeleteCodeIfPasswordless(String userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));
        if (userRepo.getPasswordHash(userId).isPresent()) {
            return; // password account — no code needed
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return; // no address to send to; the request path will 401 without a code
        }
        String code = authChallenge.createDeleteCode(userId);
        safeEmail(user.getEmail(), "Your Apalchi account deletion code",
                "<p>Your account deletion confirmation code is <b>" + code + "</b>. "
                        + "It expires in 10 minutes. If you didn't request this, ignore this email "
                        + "and your account stays safe.</p>");
    }

    /**
     * Requests deletion of the authenticated account. Re-authenticates, enforces the
     * org-owner / parent guards, cancels Stripe (or flags IAP), transitions to
     * DELETION_PENDING (bumping the epoch), and fires best-effort emails.
     *
     * @param password the account password (password accounts) or null
     * @param code     the emailed DELETE_ACCOUNT code (passwordless accounts) or null
     */
    @Transactional
    public DeletionRequestResult requestDeletion(String userId, String password, String code) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        // 1. Rate-limit + re-authenticate. A bearer token alone is never enough.
        var rl = rateLimiter.tryAcquire("acct-del:" + userId, REAUTH_LIMIT, REAUTH_WINDOW_MS);
        if (!rl.allowed()) {
            throw new BusinessException(
                    "Too many attempts. Try again in " + rl.retryAfterSeconds() + "s.", 429);
        }
        reauthenticate(userId, password, code);

        // 2. Org owner block-unless-empty (also re-checked at purge time).
        if (!centreAccess.isOwnedCentreEmpty(userId)) {
            throw new CentreNotEmptyException();
        }
        // 3. Parent with linked children — mirror the purge engine's guard so the
        //    account isn't left PENDING then aborted by the reaper forever.
        if (userRepo.countByParentId(userId) > 0) {
            throw new BusinessException(
                    "Please unlink all child accounts before deleting your account.", 409);
        }

        // 4. Subscription: cancel Stripe server-side, or flag store-managed IAP.
        boolean needsManualCancellation = cancelSubscriptionOrDetectIap(userId);

        // 5. State transition — DELETION_PENDING + stamp + epoch bump (logs out everywhere).
        Instant now = Instant.now();
        userRepo.markDeletionPending(userId, now);
        Instant graceEndsAt = now.plus(graceDays, ChronoUnit.DAYS);

        // 6. Best-effort emails (never block the state change). One restore token is
        //    shared by the requester and, for a child, their parent — either can cancel.
        String restoreToken = mintRestoreTokenSafely(userId);
        sendRequestedEmail(user, graceEndsAt, restoreToken);
        notifyParentIfChild(user, graceEndsAt, restoreToken);

        log.info("[AccountDeletion] Requested userId={} graceEndsAt={} iap={}",
                userId, graceEndsAt, needsManualCancellation);
        return new DeletionRequestResult(graceEndsAt, needsManualCancellation);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void reauthenticate(String userId, String password, String code) {
        Optional<String> hash = userRepo.getPasswordHash(userId);
        if (hash.isPresent()) {
            if (password == null || !passwordEncoder.matches(password, hash.get())) {
                throw new BusinessException("Incorrect password", 401);
            }
        } else {
            // Passwordless (social): require a valid emailed DELETE_ACCOUNT code.
            if (!authChallenge.consumeDeleteCode(userId, code)) {
                throw new BusinessException("Invalid or expired confirmation code", 401);
            }
        }
    }

    /**
     * Cancels a Stripe-managed subscription server-side (best-effort), or — when the
     * live paid sub has NO Stripe subscription id — detects it as a store IAP
     * (App Store / Play), which the server cannot cancel. Returns true iff the user
     * must be told to cancel manually via the store. No Stripe call is attempted in
     * the IAP case.
     */
    private boolean cancelSubscriptionOrDetectIap(String userId) {
        var subOpt = subscriptionRepo.findById(userId);
        if (subOpt.isEmpty()) {
            return false;
        }
        var sub = subOpt.get();
        boolean liveSub = sub.status() != null && ACTIVE_SUB_STATUSES.contains(sub.status());
        boolean hasStripeSub = sub.stripeSubscriptionId() != null && !sub.stripeSubscriptionId().isBlank();

        if (liveSub && !hasStripeSub) {
            // Store-managed IAP — server can't cancel; tell the client to warn the user.
            return true;
        }
        if (hasStripeSub) {
            try {
                stripeService.cancelSubscriptionForUser(sub.stripeSubscriptionId());
            } catch (Exception e) {
                // Never block deletion on a Stripe failure.
                log.warn("[AccountDeletion] Stripe cancel failed userId={}: {}", userId, e.getMessage());
            }
        }
        return false;
    }

    private String mintRestoreTokenSafely(String userId) {
        try {
            return authChallenge.createRestoreToken(userId, graceDays);
        } catch (Exception e) {
            log.warn("[AccountDeletion] restore-token mint failed userId={}: {}", userId, e.getMessage());
            return null;
        }
    }

    private void sendRequestedEmail(User user, Instant graceEndsAt, String restoreToken) {
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            return;
        }
        String body = "<p>Your Apalchi account is scheduled for permanent deletion on "
                + "<b>" + graceEndsAt + "</b>.</p>"
                + restoreLinkHtml(restoreToken, "changed your mind");
        safeEmail(user.getEmail(), "Your Apalchi account is scheduled for deletion", body);
    }

    private void notifyParentIfChild(User user, Instant graceEndsAt, String restoreToken) {
        if (user.getParentId() == null) {
            return;
        }
        userRepo.findById(user.getParentId()).ifPresent(parent -> {
            if (parent.getEmail() == null || parent.getEmail().isBlank()) {
                return;
            }
            String childName = user.getDisplayName() != null ? user.getDisplayName() : "Your child";
            String body = "<p>" + childName + "'s Apalchi account is scheduled for permanent "
                    + "deletion on <b>" + graceEndsAt + "</b>.</p>"
                    + restoreLinkHtml(restoreToken, "want to keep the account");
            safeEmail(parent.getEmail(),
                    "Your child's Apalchi account is scheduled for deletion", body);
        });
    }

    private String restoreLinkHtml(String restoreToken, String phrase) {
        if (restoreToken == null) {
            return "<p>If you " + phrase + ", sign back in before that date to cancel the deletion.</p>";
        }
        String link = webBaseUrl + "/account/restore?token=" + restoreToken;
        return "<p>If you " + phrase + ", <a href=\"" + link + "\">tap here to restore the account</a> "
                + "before that date, or simply sign back in.</p>";
    }

    private void safeEmail(String to, String subject, String html) {
        try {
            emailService.sendHtml(to, subject, html);
        } catch (Exception e) {
            log.warn("[AccountDeletion] email send failed to={}: {}", to, e.getMessage());
        }
    }
}
