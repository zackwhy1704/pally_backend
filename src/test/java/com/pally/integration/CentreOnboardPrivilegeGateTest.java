package com.pally.integration;

import com.pally.infrastructure.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the /centre/onboard privilege gap.
 *
 * <p>THE DEFECT: {@code POST /api/v1/centre/onboard} creates an organization and
 * makes the CALLER its owner, but it fell through to
 * {@code .anyRequest().authenticated()} in SecurityConfig — so ANY authenticated
 * principal could mint a centre and own it, including a mobile student account.
 * Holding a user token should never confer the right to create a centre.
 *
 * <p>Independent of the signup removal: the endpoint is reachable by any script
 * holding a valid user JWT, so this was a live gap on production regardless of
 * what the web UI offered.
 *
 * <p>ADMIN is an INTERIM gate — the intended authorisation is the centre-invite
 * token, which is not built yet. These tests pin the CURRENT contract; when the
 * invite path lands, the non-admin case must become "rejected unless holding a
 * valid invite token", NOT "allowed again".
 *
 * <p>Uses {@link HttpClient} rather than the inherited TestRestTemplate helpers
 * on purpose: a Spring Security filter-chain rejection makes TestRestTemplate
 * throw {@code ResourceAccessException("cannot retry due to server
 * authentication, in streaming mode")} instead of surfacing the status, so the
 * assertion could never see the rejection it is here to prove.
 *
 * <p>The denial status is 401, not 403 — verified to be identical to the
 * established {@code /api/v1/admin/**} gate, which also answers 401 to a USER
 * token. Each test therefore ALSO asserts that no organization was created,
 * because that is the actual security property; the status code is only how it
 * surfaces.
 */
class CentreOnboardPrivilegeGateTest extends IntegrationTestBase {

    @Autowired private JwtService jwt;

    private HttpResponse<String> onboardAs(String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/centre/onboard"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString("{\"centreName\":\"Gate Test Centre\"}"))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Orgs owned by this user — the property that actually matters. */
    private int orgsOwnedBy(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organizations WHERE owner_user_id = ?", Integer.class, userId);
    }

    @Test
    void ordinaryAuthenticatedUser_cannotCreateACentre() throws Exception {
        // THE EXACT CALL THAT WAS THE BUG: a plain USER token used to be enough
        // to create an organization and become its owner.
        String userId = newUserRow();

        assertThat(onboardAs(jwt.generateToken(userId, "USER")).statusCode())
                .as("a plain user token must NOT be able to mint a centre")
                .isEqualTo(401);
        assertThat(orgsOwnedBy(userId))
                .as("no organization may exist for a rejected caller")
                .isZero();
    }

    @Test
    void aStudentAccount_cannotCreateACentre() throws Exception {
        // The concrete scenario that made this worth fixing ahead of the rest:
        // the mobile consumer funnel hands every learner a valid user token.
        String userId = newUserRow();

        assertThat(onboardAs(jwt.generateToken(userId, "STUDENT")).statusCode())
                .as("a student token must NOT be able to mint a centre")
                .isEqualTo(401);
        assertThat(orgsOwnedBy(userId)).isZero();
    }

    @Test
    void unauthenticatedCaller_isRejected() throws Exception {
        // Guards against the gate being written as permitAll by accident.
        assertThat(onboardAs("not-a-real-token").statusCode()).isIn(401, 403);
    }

    @Test
    void adminToken_passesTheGate_soCentresCanStillBeProvisioned() throws Exception {
        // The interim gate must leave a working path — otherwise no centre could
        // be provisioned at all until the invite flow lands.
        //
        // A fresh admin owns no centre yet, so this is the real create path —
        // asserting 200 AND the resulting row keeps this from passing on a 500,
        // which an isNotIn(401,403) assertion would have allowed through.
        String adminId = newUserRow();

        assertThat(onboardAs(jwt.generateToken(adminId, "ADMIN")).statusCode())
                .as("ADMIN must still pass the security gate")
                .isEqualTo(200);
        assertThat(orgsOwnedBy(adminId))
                .as("the allowed path must genuinely create the centre")
                .isEqualTo(1);
    }
}
