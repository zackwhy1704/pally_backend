package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * THE INCIDENT REGRESSION PINS. Signup (`/onboard/quick`) was a register-OR-login
 * upsert: an existing email was silently logged in — tokens issued for that account —
 * so "signup" behaved as "login" (the pre-account-takeover failure). These assert the
 * invariant end-to-end: an account-creating endpoint NEVER issues a token for a
 * pre-existing account, under any input (correct password, stale bearer, case variant).
 */
class SignupSecurityIntegrationTest extends IntegrationTestBase {

    private ResponseEntity<Map> quickOnboard(String email, String password, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of(
                "email", email, "password", password,
                "subject", "MATHS", "level", "primary 4",
                "acceptedTerms", true);
        return restTemplate.postForEntity(
                baseUrl() + "/api/v1/onboard/quick", new HttpEntity<>(body, headers), Map.class);
    }

    @Test
    void quickOnboard_existingEmail_correctPassword_returns409_withNoTokenInBody() {
        registerUser("dupe@test.com", "password123"); // account already exists

        // Even the CORRECT password must not log in via a signup endpoint.
        ResponseEntity<Map> res = quickOnboard("dupe@test.com", "password123", new HttpHeaders());

        assertThat(res.getStatusCode().value()).isEqualTo(409);
        // No token anywhere in the response — signup issues none for a pre-existing account.
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().toString().toLowerCase()).doesNotContain("token");
        assertThat(((Map<?, ?>) res.getBody()).get("data")).isNull();
    }

    @Test
    void quickOnboard_existingEmail_ignoresStaleAuthorizationHeader_stillReturns409() {
        var auth = registerUser("stale@test.com", "password123");
        HttpHeaders stale = new HttpHeaders();
        stale.setBearerAuth(auth.token()); // a live session for THIS account, presented on signup

        ResponseEntity<Map> res = quickOnboard("stale@test.com", "password123", stale);

        // The endpoint ignores inbound Authorization for creation logic — no token minted.
        assertThat(res.getStatusCode().value()).isEqualTo(409);
        assertThat(res.getBody().toString().toLowerCase()).doesNotContain("token");
    }

    @Test
    void webRegister_explicitAdultRole_noBirthYear_succeedsAsAdult() {
        // INVARIANT PRESERVED, ENTRY POINT MOVED. The web /auth/register door is
        // closed (403), but the rule this pins — role:"adult" is AGE-EXEMPT, so a
        // centre admin registers with no birth year and is NOT rejected by the
        // student birth-year requirement — is exactly what invite-based account
        // creation depends on, since it registers invitees as "adult".
        var result = authServiceForFixtures.register(
                "admin-" + System.nanoTime() + "@test.com", "password123",
                "Centre Admin", "adult", null, null, true);

        assertThat(result.token()).isNotNull(); // a real session, not a rejection
        assertThat(result.userId()).isNotNull();
    }

    @Test
    void webRegister_blankRole_noBirthYear_isRejected400_noAccountCreated() {
        // FAIL-CLOSED, still enforced. The controller-level blank-shape check went
        // away with the endpoint, but the underlying rule did not: AuthService
        // requires a birth year for any account that is not age-exempt, so a blank
        // role with no birth year is refused and mints nothing. That is the
        // "unknown shape -> age-exempt ADULT" landmine this codebase spent a branch
        // killing, and it must not come back through the invite path.
        String email = "blankshape-" + System.nanoTime() + "@test.com";

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                authServiceForFixtures.register(
                        email, "password123", "No Shape", null, null, null, true))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class);

        // No account row created: the same email registers cleanly with a valid
        // shape. Had the blank-shape call created it, this would be a duplicate.
        var ok = authServiceForFixtures.register(
                email, "password123", "No Shape", "adult", null, null, true);
        assertThat(ok.userId()).isNotNull();
    }

    @Test
    void quickOnboard_existingEmail_caseVariant_returns409_viaCanonicalKey() {
        registerUser("Mixed@test.com", "password123");

        // A different-cased spelling of the same address is the SAME account (canonical key).
        ResponseEntity<Map> res = quickOnboard("MIXED@test.com", "password123", new HttpHeaders());

        assertThat(res.getStatusCode().value()).isEqualTo(409);
    }

    /// EULA/Terms-of-Use gate, exercised through the REAL Spring @Valid pipeline
    /// (the unit test on QuickOnboardService proves the service-level defense-in-
    /// depth check; this proves the DTO-level @AssertTrue actually wires up and
    /// blocks BEFORE the controller method body runs). Omitting the field
    /// entirely must reject exactly like sending false — never silently accepted.
    @Test
    void quickOnboard_termsNotAccepted_rejects400_noAccountCreated() {
        String email = "noterms-" + System.nanoTime() + "@test.com";
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        // role:"adult" (age-exempt path — see webRegister_explicitAdultRole_noBirthYear_
        // succeedsAsAdult above) so the ONLY thing blocking either request is the terms
        // gate, not the unrelated fail-closed role/birthYear requirement.
        Map<String, Object> body = Map.of(
                "email", email, "password", "password123",
                "subject", "MATHS", "level", "primary 4", "role", "adult");
        // acceptedTerms field omitted entirely.
        ResponseEntity<Map> res = restTemplate.postForEntity(
                baseUrl() + "/api/v1/onboard/quick", new HttpEntity<>(body, h), Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(400);

        // No account created: signing up again with the SAME email and acceptedTerms:true
        // now SUCCEEDS (201). Had the first request created the account, this would 409.
        Map<String, Object> retryBody = Map.of(
                "email", email, "password", "password123",
                "subject", "MATHS", "level", "primary 4", "role", "adult",
                "acceptedTerms", true);
        ResponseEntity<Map> retry = restTemplate.postForEntity(
                baseUrl() + "/api/v1/onboard/quick", new HttpEntity<>(retryBody, h), Map.class);
        assertThat(retry.getStatusCode().value()).isEqualTo(201);
    }
}
