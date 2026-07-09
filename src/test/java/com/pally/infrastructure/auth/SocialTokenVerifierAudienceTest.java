package com.pally.infrastructure.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 6th fail-open of the null-config family: /auth/apple (and /auth/google) skipped the
 * audience check when the client-id config was unset, so a validly-signed token issued to
 * ANY other app replayed here and impersonated that app's user. FAIL CLOSED: no configured
 * audience ⇒ the social sign-in is rejected outright, never waved through.
 */
class SocialTokenVerifierAudienceTest {

    @Test
    void noConfiguredAudience_rejectsSignIn_failClosed() {
        assertThatThrownBy(() -> SocialTokenVerifier.requireConfiguredAudience(List.of(), "Apple"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("not configured");
        assertThatThrownBy(() -> SocialTokenVerifier.requireConfiguredAudience(null, "Google"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void configuredAudience_passesTheGuard() {
        assertThatCode(() ->
                SocialTokenVerifier.requireConfiguredAudience(List.of("client-123.apps"), "Google"))
                .doesNotThrowAnyException();
    }
}
