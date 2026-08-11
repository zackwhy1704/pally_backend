package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the auth flow: register, login, token validation.
 */
class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void register_validCredentials_returns201WithToken() {
        AuthResult result = registerUser("auth-reg-" + System.nanoTime() + "@test.com", "password123");
        assertThat(result.userId()).isNotBlank();
        assertThat(result.token()).isNotBlank();
    }

    @Test
    void register_duplicateEmail_returns409() {
        String email = "auth-dup-" + System.nanoTime() + "@test.com";
        registerUser(email, "password123");
        // Second registration with same email (role:"adult" so it passes the register
        // shape guard and reaches the duplicate-email check, not the 400 blank-shape guard).
        Map<String, Object> body = Map.of(
                "email", email,
                "password", "password456",
                "displayName", "Dup User",
                "role", "adult",
                "acceptedTerms", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void login_validCredentials_returns200WithToken() {
        String email = "auth-login-" + System.nanoTime() + "@test.com";
        registerUser(email, "password123");
        AuthResult result = loginUser(email, "password123");
        assertThat(result.userId()).isNotBlank();
        assertThat(result.token()).isNotBlank();
    }

    @Test
    void login_wrongPassword_returns401() {
        registerUser("auth-test-wrong-" + System.nanoTime() + "@test.com", "password123");
        // Use a plain RestTemplate to avoid TestRestTemplate's auth retry on 401
        RestTemplate plain = new RestTemplate();
        Map<String, String> body = Map.of(
                "email", "auth-test-wrong-nonexistent@test.com",
                "password", "wrongpassword");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<Map> response = plain.postForEntity(
                    baseUrl() + "/api/v1/auth/login",
                    new HttpEntity<>(body, headers), Map.class);
            assertThat(response.getStatusCode().value()).isEqualTo(401);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(401);
        }
    }

    @Test
    void protectedRoute_withValidToken_returns200() {
        AuthResult auth = registerUser("auth-prot-" + System.nanoTime() + "@test.com", "password123");
        ResponseEntity<Map> response = get("/api/v1/auth/me", auth.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void protectedRoute_withoutToken_returns401() {
        RestTemplate plain = new RestTemplate();
        try {
            ResponseEntity<Map> response = plain.exchange(
                    baseUrl() + "/api/v1/auth/me",
                    HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()),
                    Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void protectedRoute_withInvalidToken_returns401() {
        RestTemplate plain = new RestTemplate();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not.a.valid.jwt.token");
        try {
            ResponseEntity<Map> response = plain.exchange(
                    baseUrl() + "/api/v1/auth/me",
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } catch (org.springframework.web.client.HttpClientErrorException e) {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    void register_invalidEmail_returns400() {
        // acceptedTerms:true so this unambiguously proves the EMAIL validation is what
        // rejects the request, not the (also-missing) terms field.
        Map<String, Object> body = Map.of(
                "email", "not-an-email",
                "password", "password123",
                "displayName", "Bad Email",
                "acceptedTerms", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_shortPassword_returns400() {
        // acceptedTerms:true so this unambiguously proves the PASSWORD validation is what
        // rejects the request, not the (also-missing) terms field.
        Map<String, Object> body = Map.of(
                "email", "auth-test-short@test.com",
                "password", "short",
                "displayName", "Short Pass",
                "acceptedTerms", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// EULA/Terms-of-Use gate on the memoly web signup path — exercised through the
    /// REAL Spring @Valid pipeline. Omitting the field entirely must reject exactly
    /// like sending false — never silently accepted. Mirrors
    /// SignupSecurityIntegrationTest.quickOnboard_termsNotAccepted_rejects400_noAccountCreated
    /// for the sibling entry point.
    @Test
    void register_termsNotAccepted_rejects400_noAccountCreated() {
        String email = "noterms-web-" + System.nanoTime() + "@test.com";
        Map<String, Object> body = Map.of(
                "email", email, "password", "password123",
                "displayName", "No Terms", "role", "adult");
        // acceptedTerms field omitted entirely.
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(body, headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // No account created: registering again with the SAME email and
        // acceptedTerms:true now SUCCEEDS (201). Had the first request created the
        // account, this would 409.
        Map<String, Object> retryBody = Map.of(
                "email", email, "password", "password123",
                "displayName", "No Terms", "role", "adult", "acceptedTerms", true);
        ResponseEntity<Map> retry = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(retryBody, headers), Map.class);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
