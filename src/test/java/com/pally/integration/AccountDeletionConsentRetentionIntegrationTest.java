package com.pally.integration;

import com.pally.domain.account.usecase.DeleteAccountUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB proof of the LOCKED consent-retention decision — the one behaviour unit tests
 * cannot cover: after V119 dropped the {@code ON DELETE CASCADE} FK, purging a user must
 * ERASE the user row while the consent_records / consent_requests EVIDENCE survives with
 * its identity link intact, and only the reusable approval token is scrubbed.
 */
class AccountDeletionConsentRetentionIntegrationTest extends IntegrationTestBase {

    @Autowired
    DeleteAccountUseCase deleteAccountUseCase;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void purge_erasesUser_butRetainsConsentProof_withTokenScrubbed() {
        var auth = registerConsentedUser("consent-retain@test.com", "pw123456");
        String uid = auth.userId();

        // Seed the two consent-proof rows (a self-consent record + a parental-consent
        // request with a LIVE token). Before V119 these would cascade away on purge.
        jdbc.update("INSERT INTO consent_records"
                + "(id,user_id,consenter,method,purposes,policy_version,created_at) "
                + "VALUES (?,?,?,?,?,?, now())",
                "cro-1", uid, "PARENT", "EMAIL_LINK", "[\"AI_DATA_TRANSFER\"]", "v1");
        jdbc.update("INSERT INTO consent_requests"
                + "(id,child_user_id,parent_email,token,status,created_at,expires_at) "
                + "VALUES (?,?,?,?,?, now(), now() + interval '7 days')",
                "cr-1", uid, "parent@test.com", "live-secret-token-abc", "APPROVED");

        // Purge via the very engine the reaper calls.
        deleteAccountUseCase.execute(uid, null, null);

        // The user is ERASED.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=?", Integer.class, uid)).isZero();

        // Consent PROOF SURVIVES the erasure (this is what V119's FK-drop buys — a live FK
        // would have cascade-deleted these the instant the user row went).
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM consent_records WHERE id='cro-1'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM consent_requests WHERE id='cr-1'", Integer.class)).isEqualTo(1);

        // The IDENTITY LINK is preserved — the identity IS the evidence ("this child's
        // guardian consented, on this date, via this mechanism").
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM consent_records WHERE id='cro-1'", String.class)).isEqualTo(uid);
        assertThat(jdbc.queryForObject(
                "SELECT child_user_id FROM consent_requests WHERE id='cr-1'", String.class)).isEqualTo(uid);

        // MINIMIZATION: the reusable approval token is scrubbed to a tombstone (retain the
        // evidence, not a live secret).
        assertThat(jdbc.queryForObject(
                "SELECT token FROM consent_requests WHERE id='cr-1'", String.class))
                .startsWith("PURGED:")
                .doesNotContain("live-secret-token-abc");

        // Cleanup the retained rows — the user is gone, so @AfterEach's email-join won't.
        jdbc.update("DELETE FROM consent_records WHERE user_id=?", uid);
        jdbc.update("DELETE FROM consent_requests WHERE child_user_id=?", uid);
    }
}
