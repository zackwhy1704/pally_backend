package com.pally.infrastructure.auth;

import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.util.EmailNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * When a signup attempt hits an ALREADY-REGISTERED email, we reject it (409, no token)
 * and — best-effort — notify the address OWNER, never the requester's screen (which
 * only ever sees a generic error, no account enumeration). This is the "someone tried
 * to sign up as you" safety signal. It must NEVER block or fail the 409 response, and is
 * rate-limited per address so it can't be used to spam a victim.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DuplicateSignupNotifier {

    private final EmailService emailService;
    private final SlidingWindowRateLimiter rateLimiter;

    /// At most 1 notification per address per hour — a duplicate-signup email must
    /// not become a spam vector against the real owner.
    private static final int LIMIT = 1;
    private static final long WINDOW_MS = 60 * 60 * 1000L;

    /** Fire-and-forget. Swallows everything — the caller's 409 must not depend on it. */
    public void notifyOwner(String email) {
        try {
            String canonical = EmailNormalizer.canonical(email);
            if (canonical == null || canonical.isBlank()) return;
            SlidingWindowRateLimiter.Result r =
                    rateLimiter.tryAcquire("dupsignup:" + canonical, LIMIT, WINDOW_MS);
            if (!r.allowed()) return; // already notified recently — stay quiet
            emailService.sendHtml(canonical, "Did you try to create an Apalchi account?",
                    "<p>Someone just tried to create an Apalchi account with this email address.</p>"
                    + "<p>If this was you, you already have an account — just <strong>sign in</strong> "
                    + "instead. If you forgot your password, you can reset it.</p>"
                    + "<p>If this wasn't you, you can safely ignore this email — no account was created "
                    + "or changed.</p>");
        } catch (Exception e) {
            log.warn("[Auth] duplicate-signup notify failed (ignored): {}", e.getMessage());
        }
    }
}
