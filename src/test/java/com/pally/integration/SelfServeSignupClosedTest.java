package com.pally.integration;

import com.pally.infrastructure.auth.SocialTokenVerifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Pins the closure of self-serve web signup (invite-only teacher/centre access).
 *
 * <p>THE POINT OF THESE TESTS: closing signup must NOT close SIGN-IN. Google
 * sign-in and Google sign-up are the SAME call ({@code POST /auth/google});
 * {@code AuthService.signInWithSocial} branches server-side between "existing
 * user" and "create". Only the create branch may be closed. Getting this wrong
 * in either direction is the whole risk of the change:
 * <ul>
 *   <li>too permissive → signup is not actually closed;</li>
 *   <li>too strict → every existing Google user is locked out of their account.</li>
 * </ul>
 * The rejection and the successful sign-in are therefore asserted in the SAME
 * test run, not in separate files that could drift apart.
 *
 * <p>Removing the {@code /signup} page alone would not have closed signup:
 * {@code /auth/register} is permitAll, and the memoly LOGIN page also calls
 * {@code /auth/google} — so an unknown Google account could create an account
 * from a page that never mentions signing up.
 */
class SelfServeSignupClosedTest extends IntegrationTestBase {

    private void googleTokenResolvesTo(String email, String subject) {
        when(socialTokenVerifier.verifyGoogle(anyString(), any()))
                .thenReturn(new SocialTokenVerifier.VerifiedClaims(
                        email, true, "Test Teacher", subject, "google"));
    }

    // ── /auth/register is closed ─────────────────────────────────────────────

    @Test
    void register_isClosed_returns403() {
        var response = post("/api/v1/auth/register", null, Map.of(
                "email", "newsignup-" + System.nanoTime() + "@test.com",
                "password", "password123",
                "role", "adult",
                "acceptedTerms", true));

        assertThat(response.getStatusCode().value())
                .as("self-serve registration must be refused, not merely unlinked from the UI")
                .isEqualTo(403);
    }

    @Test
    void register_isRefusedBeforeCreatingAnything() {
        String email = "ghost-" + System.nanoTime() + "@test.com";

        post("/api/v1/auth/register", null, Map.of(
                "email", email, "password", "password123",
                "role", "adult", "acceptedTerms", true));

        Integer created = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(created)
                .as("a refused registration must leave no account behind")
                .isZero();
    }

    // ── THE PAIR: unknown rejected, existing still signs in ──────────────────

    @Test
    void googleSignIn_unknownAccount_isRejected_butAnExistingUserStillSignsIn() {
        // ---- (1) EXISTING user: seed by signing them in while... they exist. ----
        // An account that already exists is matched by provider-sub or verified
        // email in signInWithSocial BEFORE the create branch, so it must be
        // unaffected by the closure.
        String existingEmail = "existing-" + System.nanoTime() + "@test.com";
        String existingSub = "google-sub-" + System.nanoTime();
        String userId = newUserRow();
        jdbcTemplate.update(
                "UPDATE users SET email = ?, provider = 'google', provider_sub = ? WHERE id = ?",
                existingEmail, existingSub, userId);

        googleTokenResolvesTo(existingEmail, existingSub);
        var existingSignIn = post("/api/v1/auth/google", null,
                Map.of("idToken", "any", "acceptedTerms", false));

        assertThat(existingSignIn.getStatusCode().value())
                .as("an EXISTING Google user must still be able to sign in — "
                        + "closing signup must not close sign-in")
                .isEqualTo(200);

        // ---- (2) UNKNOWN account: same endpoint, same run. ----
        googleTokenResolvesTo("stranger-" + System.nanoTime() + "@test.com",
                "google-sub-unknown-" + System.nanoTime());
        var unknownSignIn = post("/api/v1/auth/google", null,
                Map.of("idToken", "any", "acceptedTerms", true));

        assertThat(unknownSignIn.getStatusCode().value())
                .as("an UNKNOWN Google account must NOT be able to create one — "
                        + "acceptedTerms:true must not buy an account")
                .isEqualTo(403);
    }

    @Test
    void googleSignIn_unknownAccount_createsNoUserRow() {
        // The status code is only how the property surfaces; this is the property.
        String stranger = "stranger-" + System.nanoTime() + "@test.com";
        googleTokenResolvesTo(stranger, "sub-" + System.nanoTime());

        post("/api/v1/auth/google", null, Map.of("idToken", "any", "acceptedTerms", true));

        Integer created = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, stranger);
        assertThat(created).isZero();
    }
}
