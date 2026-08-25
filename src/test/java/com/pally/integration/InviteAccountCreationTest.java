package com.pally.integration;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins invite-only account creation — the replacement for self-serve signup.
 *
 * <p>WHY THIS EXISTS: before this, {@code /auth/accept-invite} required an
 * authenticated caller, so the accept-invite page bounced an unauthenticated
 * invitee to {@code /login} — for an account they had no way to create once
 * signup was closed. Closing signup WITHOUT this would have locked out exactly
 * the teachers being invited. The two changes are only correct together.
 *
 * <p>THE TOKEN IS THE AUTHORISATION, so these tests care most about what a token
 * does NOT buy: an expired or already-used or unknown token must create nothing,
 * and the account email must come from the INVITE rather than the request body —
 * otherwise one leaked token would let anyone create an account under any address.
 */
class InviteAccountCreationTest extends IntegrationTestBase {

    private static final String PATH = "/api/v1/auth/accept-invite/register";

    /** Inserts an owner invite directly; mirrors CentreInviteService.createInvite. */
    private String seedInvite(String contactEmail, Instant expiresAt, Instant acceptedAt) {
        String token = "tok-" + System.nanoTime();
        // role and created_at both carry DB defaults (V81 / V80), so they are
        // deliberately omitted rather than restated here.
        jdbcTemplate.update(
                "INSERT INTO centre_invite_tokens "
                        + "(token, centre_name, contact_email, created_by, expires_at, accepted_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                token, "Invited Tuition Centre", contactEmail, "admin-seed",
                java.sql.Timestamp.from(expiresAt),
                acceptedAt == null ? null : java.sql.Timestamp.from(acceptedAt));
        return token;
    }

    private int usersWithEmail(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
    }

    @Test
    void validToken_createsTheAccountAndTheCentre() {
        String email = "invited-" + System.nanoTime() + "@test.com";
        String token = seedInvite(email, Instant.now().plus(7, ChronoUnit.DAYS), null);

        var response = post(PATH, null, Map.of(
                "token", token, "password", "password123",
                "displayName", "Invited Teacher", "acceptedTerms", true));

        assertThat(response.getStatusCode().value())
                .as("a valid invite must be able to create the invitee's account")
                .isEqualTo(201);
        assertThat(usersWithEmail(email))
                .as("the account is created under the INVITE's email")
                .isEqualTo(1);

        Integer orgs = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organizations o JOIN users u ON u.id = o.owner_user_id "
                        + "WHERE u.email = ?", Integer.class, email);
        assertThat(orgs)
                .as("accepting an OWNER invite must also create the centre they own")
                .isEqualTo(1);
    }

    @Test
    void theAccountEmailComesFromTheInvite_notTheRequestBody() {
        // If the caller could choose the email, a single leaked token would let
        // anyone create an account under an arbitrary address — the token would
        // authorise IDENTITY rather than access.
        String invitedEmail = "real-invitee-" + System.nanoTime() + "@test.com";
        String attackerEmail = "attacker-" + System.nanoTime() + "@test.com";
        String token = seedInvite(invitedEmail, Instant.now().plus(7, ChronoUnit.DAYS), null);

        post(PATH, null, Map.of(
                "token", token, "password", "password123",
                "email", attackerEmail,          // ignored by design
                "acceptedTerms", true));

        assertThat(usersWithEmail(invitedEmail)).isEqualTo(1);
        assertThat(usersWithEmail(attackerEmail))
                .as("a caller-supplied email must never determine the account created")
                .isZero();
    }

    @Test
    void expiredToken_createsNothing() {
        String email = "expired-" + System.nanoTime() + "@test.com";
        String token = seedInvite(email, Instant.now().minus(1, ChronoUnit.DAYS), null);

        var response = post(PATH, null, Map.of(
                "token", token, "password", "password123", "acceptedTerms", true));

        assertThat(response.getStatusCode().value()).isEqualTo(410);
        assertThat(usersWithEmail(email))
                .as("an expired invite must leave no account behind")
                .isZero();
    }

    @Test
    void alreadyAcceptedToken_createsNothing() {
        // A token is single-use: replaying it must not mint a second account.
        String email = "used-" + System.nanoTime() + "@test.com";
        String token = seedInvite(email, Instant.now().plus(7, ChronoUnit.DAYS), Instant.now());

        var response = post(PATH, null, Map.of(
                "token", token, "password", "password123", "acceptedTerms", true));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(usersWithEmail(email)).isZero();
    }

    @Test
    void unknownToken_createsNothing() {
        var response = post(PATH, null, Map.of(
                "token", "tok-does-not-exist", "password", "password123", "acceptedTerms", true));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void withoutAcceptedTerms_createsNothing() {
        String email = "noterms-" + System.nanoTime() + "@test.com";
        String token = seedInvite(email, Instant.now().plus(7, ChronoUnit.DAYS), null);

        var response = post(PATH, null, Map.of(
                "token", token, "password", "password123", "acceptedTerms", false));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(usersWithEmail(email)).isZero();
    }
}
