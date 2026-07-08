package com.pally.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The contradiction guard: LIVE payments must be impossible with dev-grade security.
 * It keys off the sk_live_ PREFIX so it CANNOT trip in dev/tests (a sk_test_ or blank
 * key leaves it inert) — that property is the point, and is pinned here.
 */
class EnvironmentGuardTest {

    private EnvironmentGuard guard(String key, String... profiles) {
        var env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        var g = new EnvironmentGuard(env);
        ReflectionTestUtils.setField(g, "stripeSecretKey", key);
        return g;
    }

    @Test
    void liveKey_noProdProfile_refusesToStart_namingBothFactsAndTheFix() {
        assertThatThrownBy(() -> guard("sk_live_abc123").enforce())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Stripe LIVE")
                .hasMessageContaining("prod")
                .hasMessageContaining("SPRING_PROFILES_ACTIVE=prod");
    }

    @Test
    void liveKey_prodProfile_startsCleanly() {
        assertThatCode(() -> guard("sk_live_abc123", "prod").enforce()).doesNotThrowAnyException();
        assertThatCode(() -> guard("sk_live_abc123", "production").enforce()).doesNotThrowAnyException();
    }

    @Test
    void testKey_noProfile_cannotTrip_theDevInvariant() {
        // A dev running a sk_test_ key with no profile must boot normally.
        assertThatCode(() -> guard("sk_test_abc123").enforce()).doesNotThrowAnyException();
    }

    @Test
    void blankOrNullKey_cannotTrip_regardlessOfProfile() {
        assertThatCode(() -> guard("").enforce()).doesNotThrowAnyException();
        assertThatCode(() -> guard(null).enforce()).doesNotThrowAnyException();
    }
}
