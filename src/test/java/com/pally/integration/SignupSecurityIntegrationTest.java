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
                "subject", "MATHS", "level", "primary 4");
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
    void quickOnboard_existingEmail_caseVariant_returns409_viaCanonicalKey() {
        registerUser("Mixed@test.com", "password123");

        // A different-cased spelling of the same address is the SAME account (canonical key).
        ResponseEntity<Map> res = quickOnboard("MIXED@test.com", "password123", new HttpHeaders());

        assertThat(res.getStatusCode().value()).isEqualTo(409);
    }
}
