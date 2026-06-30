package com.pally.domain.subscription;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.push.FcmService;
import com.pally.infrastructure.ratelimit.SlidingWindowRateLimiter;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2: lets a logged-in mobile user send themselves the web billing link
 * via email and/or push notification, so they can finish an upgrade in their
 * phone's browser (where web Stripe checkout is available without App-Store
 * commission).
 *
 * <p>The web billing URL is currently a plain link to
 * {@code <app.web-base-url>/account/billing}; the user authenticates on the
 * web normally. We deliberately do NOT mint a bearer-token-in-email here —
 * there is no existing billing-page-scoped magic-link consumer on web, and
 * inventing one from scratch would be a security risk. See the TODO below.
 *
 * <p>Both {@link EmailService} and {@link FcmService} swallow their own
 * failures (a missing FCM token / unconfigured provider is a silent no-op),
 * so {@code wasDispatched} reflects "we attempted a real send because the
 * channel is configured", not "the device definitely received it".
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UpgradeLinkService {

    /** Path appended to the web base URL to reach the billing page. */
    static final String BILLING_PATH = "/account/billing";

    /** Abuse cap: at most this many upgrade-link sends per user per window. */
    private static final int SEND_LIMIT = 5;
    private static final long SEND_WINDOW_MS = 60L * 60_000; // 1 hour

    private static final String CHANNEL_EMAIL = "email";
    private static final String CHANNEL_PUSH = "push";

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final FcmService fcmService;
    private final SlidingWindowRateLimiter rateLimiter;

    @Value("${app.web-base-url:https://apalchi.com}")
    private String webBaseUrl;

    /**
     * Sends the web billing link to the authenticated user over the requested
     * channels. A null/empty {@code channels} list defaults to BOTH.
     *
     * @return {@code { "sent": { "email": <bool>, "push": <bool> } }} reflecting
     *         which channels were actually dispatched.
     */
    public Map<String, Object> sendUpgradeLink(String userId, List<String> channels) {
        if (userId == null || userId.isBlank()) {
            // Defence in depth — the controller's @AuthenticationPrincipal guarantees
            // this, but never trust the caller to have done so.
            throw new BusinessException("Authentication required", 401);
        }

        var limit = rateLimiter.tryAcquire("upgrade-link:" + userId, SEND_LIMIT, SEND_WINDOW_MS);
        if (!limit.allowed()) {
            throw new BusinessException(
                    "Too many requests. Try again in " + limit.retryAfterSeconds() + "s.", 429);
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException("User not found", 404));

        Set<String> requested = normalizeChannels(channels);

        // TODO(phase2.1): pre-auth magic token. When a single-use, <=15-min,
        // billing-page-scoped token mechanism + a web consumer exist, mint a
        // token here and append "?t=<token>" so the user lands authenticated.
        // Until then we send the plain link and the user signs in on web.
        String billingUrl = webBaseUrl + BILLING_PATH;

        boolean emailSent = false;
        boolean pushSent = false;

        if (requested.contains(CHANNEL_EMAIL)) {
            emailSent = dispatchEmail(user, billingUrl);
        }
        if (requested.contains(CHANNEL_PUSH)) {
            pushSent = dispatchPush(user.getId());
        }

        log.info("[UpgradeLink] user={} channels={} emailSent={} pushSent={}",
                userId, requested, emailSent, pushSent);

        return Map.of("sent", Map.of(
                CHANNEL_EMAIL, emailSent,
                CHANNEL_PUSH, pushSent));
    }

    /**
     * Returns the requested channels, lower-cased and de-duped. Unknown channel
     * names are ignored. A null/empty request defaults to BOTH channels.
     */
    private Set<String> normalizeChannels(List<String> channels) {
        if (channels == null || channels.isEmpty()) {
            return Set.of(CHANNEL_EMAIL, CHANNEL_PUSH);
        }
        Set<String> out = new LinkedHashSet<>();
        for (String c : channels) {
            if (c == null) continue;
            String v = c.trim().toLowerCase(Locale.ROOT);
            if (CHANNEL_EMAIL.equals(v) || CHANNEL_PUSH.equals(v)) {
                out.add(v);
            }
        }
        // If the caller sent only unknown channel names, fall back to BOTH
        // rather than silently sending nothing.
        if (out.isEmpty()) {
            return Set.of(CHANNEL_EMAIL, CHANNEL_PUSH);
        }
        return out;
    }

    /** @return true if we attempted a real email send (provider configured + a recipient address). */
    private boolean dispatchEmail(User user, String billingUrl) {
        String to = user.getEmail();
        if (to == null || to.isBlank()) {
            log.warn("[UpgradeLink] user={} has no email — skipping email channel", user.getId());
            return false;
        }
        if (!emailService.isConfigured()) {
            return false;
        }
        emailService.sendHtml(to, "Finish your Apalchi upgrade", buildEmailHtml(billingUrl));
        return true;
    }

    /**
     * @return true if we attempted a real push send (FCM configured).
     *
     * <p>App Store 3.1.1 anti-steering: the push is delivered inside Apple's
     * ecosystem, so it must NOT carry a web-checkout CTA or any billing URL.
     * It only nudges the user to the sanctioned out-of-app channel (email);
     * the data payload deliberately carries no URL.
     */
    private boolean dispatchPush(String userId) {
        if (!fcmService.isConfigured()) {
            return false;
        }
        fcmService.sendToUser(
                userId,
                "Your upgrade link is ready",
                "Check your email to finish on the web.",
                Map.of());
        return true;
    }

    private String buildEmailHtml(String billingUrl) {
        return """
                <div style="font-family:Nunito,Arial,sans-serif;color:#1F1733;line-height:1.5">
                  <h2 style="color:#7042ED;margin-bottom:8px">Finish your Apalchi upgrade</h2>
                  <p>You're one step away from unlocking Apalchi premium. Continue your
                     checkout securely in your browser:</p>
                  <p style="margin:24px 0">
                    <a href="%s"
                       style="background:#7042ED;color:#ffffff;text-decoration:none;
                              padding:12px 24px;border-radius:8px;display:inline-block;
                              font-weight:700">Open billing page</a>
                  </p>
                  <p style="color:#6B618A;font-size:13px">
                    Or paste this link into your browser:<br>
                    <a href="%s" style="color:#7042ED">%s</a>
                  </p>
                </div>
                """.formatted(billingUrl, billingUrl, billingUrl);
    }
}
