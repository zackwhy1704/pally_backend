package com.pally.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Fail-closed startup check for production secrets — refuses to boot a
 * prod profile with the dev defaults or a missing webhook signing key.
 * Dev/test profiles boot normally so local runs don't need real keys.
 *
 * <p>What it catches:
 *  - Production deployed with the committed dev JWT secret (would let
 *    anyone mint admin tokens).
 *  - Production deployed with STRIPE_SECRET_KEY set but no
 *    STRIPE_WEBHOOK_SECRET (so the webhook never verifies signatures
 *    and Stripe state can be forged).
 */
@Configuration
@Slf4j
public class SecretsValidator {

    private static final String DEV_JWT_DEFAULT =
            "dev-secret-min-32-chars-long-here-!!";
    private static final int MIN_JWT_SECRET_CHARS = 32;

    @Value("${jwt.secret:}")
    private String jwtSecret;

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    @Value("${stripe.webhook-secret:}")
    private String stripeWebhookSecret;

    @Bean
    public ApplicationRunner secretsValidatorRunner(Environment env) {
        return args -> {
            if (!isProd(env)) {
                log.info("[Secrets] non-prod profile; skipping strict secret checks");
                return;
            }
            validateJwt();
            validateStripe();
            log.info("[Secrets] prod secret checks passed");
        };
    }

    private boolean isProd(Environment env) {
        // Belt and braces: the prod PROFILE is the normal trigger, but a LIVE Stripe
        // key (sk_live_) is the backstop — strict secret checks run whenever real
        // payments are configured, even if the profile was forgotten (the incident).
        // EnvironmentGuard already refuses to start on sk_live_ + non-prod, so this is
        // redundant defence; it still matters if that guard is ever disabled/excluded.
        if (EnvironmentGuard.isLiveStripe(stripeSecretKey)) {
            return true;
        }
        return EnvironmentGuard.isProdProfile(env);
    }

    private void validateJwt() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "JWT_SECRET must be set in prod (never blank).");
        }
        if (jwtSecret.equals(DEV_JWT_DEFAULT)) {
            throw new IllegalStateException(
                    "JWT_SECRET is still the dev default — refusing to boot prod.");
        }
        if (jwtSecret.length() < MIN_JWT_SECRET_CHARS) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_JWT_SECRET_CHARS
                            + " chars in prod.");
        }
    }

    private void validateStripe() {
        boolean liveStripe = stripeSecretKey != null && !stripeSecretKey.isBlank();
        if (!liveStripe) {
            // Mock mode in prod is unusual but allowed (e.g. during a
            // billing migration). We log loudly so it's obvious in the
            // boot output.
            log.warn("[Secrets] STRIPE_SECRET_KEY blank in prod — running mock mode");
            return;
        }
        if (stripeWebhookSecret == null || stripeWebhookSecret.isBlank()) {
            throw new IllegalStateException(
                    "STRIPE_WEBHOOK_SECRET must be set when STRIPE_SECRET_KEY is set "
                            + "— refusing to boot a live Stripe with no webhook verification.");
        }
    }
}
