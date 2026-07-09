package com.pally.integration;

import com.pally.infrastructure.auth.AuthChallengeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB proof of the PUBLIC delete-by-email flow (Phase 2 / B2), esp. the LOCKED riders:
 * non-enumeration (identical response + no token minted for a non-account) and the confirm
 * token running the SAME grace pipeline as the authenticated request.
 */
class AccountDeletionPublicByEmailIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuthChallengeService authChallenge;

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private int deleteConfirmRows(String userId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM auth_challenges WHERE user_id=? AND purpose='DELETE_CONFIRM'",
                Integer.class, userId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestByEmail_isNonEnumerating_mintsTokenOnlyForRealAccount() {
        var auth = registerConsentedUser("public-del@test.com", "pw123456");

        ResponseEntity<Map> known = restTemplate.postForEntity(
                baseUrl() + "/api/v1/account/delete/request-by-email",
                new HttpEntity<>(Map.of("email", "public-del@test.com"), json()), Map.class);
        ResponseEntity<Map> unknown = restTemplate.postForEntity(
                baseUrl() + "/api/v1/account/delete/request-by-email",
                new HttpEntity<>(Map.of("email", "nobody-here@test.com"), json()), Map.class);

        // Identical status + body regardless of whether the account exists.
        assertThat(known.getStatusCode().value()).isEqualTo(200);
        assertThat(unknown.getStatusCode().value()).isEqualTo(200);
        assertThat(known.getBody().get("data")).isEqualTo(unknown.getBody().get("data"));

        // But a confirm token is minted ONLY for the real account (sync mint).
        assertThat(deleteConfirmRows(auth.userId())).isEqualTo(1);
    }

    @Test
    void confirmByToken_runsGracePipeline_movesAccountToPending() {
        var auth = registerConsentedUser("public-confirm@test.com", "pw123456");
        String token = authChallenge.createDeleteConfirmToken(auth.userId()); // plaintext for the test

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl() + "/api/v1/account/delete/confirm",
                new HttpEntity<>(Map.of("token", token), json()), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // Same pipeline as the authenticated request: account is now in the grace window.
        assertThat(jdbc.queryForObject(
                "SELECT account_status FROM users WHERE id=?", String.class, auth.userId()))
                .isEqualTo("DELETION_PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT deletion_requested_at FROM users WHERE id=?",
                java.sql.Timestamp.class, auth.userId())).isNotNull();
    }

    @Test
    void confirmByToken_invalidToken_is400_andDoesNotTransition() {
        var auth = registerConsentedUser("public-badtoken@test.com", "pw123456");

        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl() + "/api/v1/account/delete/confirm",
                new HttpEntity<>(Map.of("token", "not-a-real-token"), json()), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT account_status FROM users WHERE id=?", String.class, auth.userId()))
                .isEqualTo("ACTIVE");
    }
}
