package com.pally.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The belt-and-braces backstop: strict secret checks must run whenever REAL payments
 * are configured (sk_live_), even if the prod profile was forgotten — the exact
 * incident. Conversely, a sk_test_ key with no profile must still skip (dev).
 */
class SecretsValidatorBackstopTest {

    private ApplicationRunner runner(String stripeKey, String webhook, String jwt, String... profiles) {
        var v = new SecretsValidator();
        ReflectionTestUtils.setField(v, "jwtSecret", jwt);
        ReflectionTestUtils.setField(v, "stripeSecretKey", stripeKey);
        ReflectionTestUtils.setField(v, "stripeWebhookSecret", webhook);
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return v.secretsValidatorRunner(env);
    }

    @Test
    void liveStripe_noProdProfile_stillRunsStrictChecks() {
        // sk_live_ + NO prod profile + blank webhook → strict Stripe check must fire
        // (the backstop), so it throws for the missing webhook. Valid JWT so we reach
        // the Stripe check.
        var r = runner("sk_live_key", "", "a-valid-jwt-secret-32-chars-minimum-xx");
        assertThatThrownBy(() -> r.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("STRIPE_WEBHOOK_SECRET");
    }

    @Test
    void testStripe_noProfile_skipsStrictChecks_theDevPath() {
        // sk_test_ + no profile → not "really prod" → skip; a short JWT is not validated.
        var r = runner("sk_test_key", "", "short");
        assertThatCode(() -> r.run(null)).doesNotThrowAnyException();
    }
}
