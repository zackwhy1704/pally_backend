package com.pally.infrastructure.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fail-fast contradiction guard: LIVE payments must never run with dev-grade security
 * autoconfiguration. If a live Stripe key ({@code sk_live_...}) is configured but the
 * {@code prod} Spring profile is NOT active, refuse to start — naming both facts and
 * the fix. This is the incident made impossible instead of merely logged.
 *
 * <p>CANNOT trip in local dev or tests: it keys off the {@code sk_live_} PREFIX, not
 * "a key is present" (a {@code sk_test_} key or a blank key leaves it inert), and not
 * "profile != prod" alone. Runs in {@code @PostConstruct} so it fails during context
 * refresh, before the web server serves a single request.
 */
@Component
@Slf4j
public class EnvironmentGuard {

    /** A live Stripe SECRET key starts with this. Test keys are {@code sk_test_}. */
    static final String LIVE_KEY_PREFIX = "sk_live_";

    @Value("${stripe.secret-key:}")
    private String stripeSecretKey;

    private final Environment env;

    public EnvironmentGuard(Environment env) {
        this.env = env;
    }

    static boolean isLiveStripe(String key) {
        return key != null && key.startsWith(LIVE_KEY_PREFIX);
    }

    static boolean isProdProfile(Environment env) {
        return Arrays.stream(env.getActiveProfiles())
                .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));
    }

    @PostConstruct
    void enforce() {
        if (!isLiveStripe(stripeSecretKey)) {
            return; // dev / test / mock — nothing to guard
        }
        if (!isProdProfile(env)) {
            throw new IllegalStateException(
                    "FATAL: Stripe LIVE mode is configured (sk_live_ secret key) but the 'prod' "
                    + "Spring profile is NOT active (active profiles="
                    + Arrays.toString(env.getActiveProfiles()) + "). Live payments would run with "
                    + "dev-grade security autoconfiguration and skipped secret checks. "
                    + "Set SPRING_PROFILES_ACTIVE=prod. Refusing to start.");
        }
        log.info("[EnvGuard] Stripe live mode + prod profile — consistent, OK.");
    }
}
