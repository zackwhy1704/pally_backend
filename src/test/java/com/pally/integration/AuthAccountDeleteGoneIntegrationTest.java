package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the fix-the-family gap that mini-0 caught (ACCOUNT DELETION Phase 2, B1):
 * DELETE /auth/account was a bearer-only IMMEDIATE hard-delete — a valid session alone
 * triggered a graceless, irreversible purge, bypassing the entire grace/re-auth/restore
 * flow. It is now 410 GONE. This pins the invariant: a bearer-only request to that
 * endpoint can NEVER reach the purge engine.
 */
class AuthAccountDeleteGoneIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void deleteAuthAccount_withValidBearer_is410Gone_andDoesNotDeleteTheUser() {
        var auth = registerConsentedUser("auth-account-gone@test.com", "pw123456");

        ResponseEntity<Map> resp = delete("/api/v1/auth/account", auth.token());

        // The endpoint is retired — a valid session does NOT (and can no longer) delete.
        assertThat(resp.getStatusCode().value()).isEqualTo(410);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=?", Integer.class, auth.userId()))
                .as("bearer-only DELETE /auth/account must never reach the purge engine")
                .isEqualTo(1);
    }
}
