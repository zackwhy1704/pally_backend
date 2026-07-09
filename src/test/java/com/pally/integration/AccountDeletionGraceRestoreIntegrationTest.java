package com.pally.integration;

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
 * End-to-end proof of the grace/restore mechanics (build order step 4), esp. the LOCKED
 * rider: login DURING grace must return the RESTORE SURFACE, never a session token — the
 * session_epoch bump is the wall and login must not be the hole in it.
 */
class AccountDeletionGraceRestoreIntegrationTest extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @Test
    @SuppressWarnings("unchecked")
    void loginDuringGrace_returnsRestoreSurface_notToken_thenRestoreReactivates() {
        var auth = registerConsentedUser("grace-restore@test.com", "pw123456");
        String uid = auth.userId();

        // Enter the deletion grace window.
        jdbc.update("UPDATE users SET account_status='DELETION_PENDING', "
                + "deletion_requested_at = now() WHERE id=?", uid);

        // 1. Login during grace → 403 ACCOUNT_SCHEDULED_FOR_DELETION with NO token.
        ResponseEntity<Map> login = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/login",
                new HttpEntity<>(Map.of("email", "grace-restore@test.com", "password", "pw123456"),
                        jsonHeaders()),
                Map.class);
        assertThat(login.getStatusCode().value()).isEqualTo(403);
        Map<String, Object> loginData = (Map<String, Object>) login.getBody().get("data");
        assertThat(loginData).isNotNull();
        assertThat(loginData.get("code")).isEqualTo("ACCOUNT_SCHEDULED_FOR_DELETION");
        assertThat(loginData.get("token")).isNull(); // the wall held — no session minted

        // 2. Restore via email+password (the login-during-grace path).
        ResponseEntity<Map> restore = restTemplate.postForEntity(
                baseUrl() + "/api/v1/account/restore",
                new HttpEntity<>(Map.of("email", "grace-restore@test.com", "password", "pw123456"),
                        jsonHeaders()),
                Map.class);
        assertThat(restore.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Map<String, Object>) restore.getBody().get("data")).get("restored"))
                .isEqualTo(true);

        // 3. The account is ACTIVE again (stamp cleared) and login now works normally.
        assertThat(jdbc.queryForObject(
                "SELECT account_status FROM users WHERE id=?", String.class, uid)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT deletion_requested_at FROM users WHERE id=?",
                java.sql.Timestamp.class, uid)).isNull();
        var relogin = loginUser("grace-restore@test.com", "pw123456");
        assertThat(relogin.token()).isNotBlank();
    }
}
