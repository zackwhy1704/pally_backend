package com.pally.integration;

import com.pally.domain.account.DeletionPurgeReaper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the anti-starvation invariant (strict-audit finding): permanently-stuck accounts at
 * the head of the oldest-first purge queue must NOT starve healthy purges behind them.
 *
 * <p>batch-size is forced to 2 so two OLDER, recently-attempted (stuck) accounts would fill
 * the whole page under a naive {@code ORDER BY deletion_requested_at ASC LIMIT 2}. The
 * backoff exclusion (deletion_last_attempt_at within the window) drops them, so a NEWER,
 * never-attempted account still gets purged. Without the fix, the newer account is starved
 * forever — silent, invisible except as purged=0.
 */
@TestPropertySource(properties = "account.deletion.purge-batch-size=2")
class AccountDeletionReaperStarvationIntegrationTest extends IntegrationTestBase {

    @Autowired
    DeletionPurgeReaper reaper;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void stuckAccountsAtQueueHead_doNotStarveAHealthyPurge() {
        // Two OLDER, recently-ATTEMPTED (stuck) accounts — would occupy the batch-size=2 head.
        var a = registerConsentedUser("stuck-a@test.com", "pw123456");
        var b = registerConsentedUser("stuck-b@test.com", "pw123456");
        // One NEWER, never-attempted, empty account behind them.
        var c = registerConsentedUser("clean-c@test.com", "pw123456");

        for (String id : List.of(a.userId(), b.userId())) {
            jdbc.update("UPDATE users SET account_status='DELETION_PENDING', "
                    + "deletion_requested_at = now() - interval '25 days', "
                    + "deletion_last_attempt_at = now() WHERE id=?", id); // within 20h backoff
        }
        jdbc.update("UPDATE users SET account_status='DELETION_PENDING', "
                + "deletion_requested_at = now() - interval '20 days', "
                + "deletion_last_attempt_at = NULL WHERE id=?", c.userId()); // eligible

        reaper.reap();

        // The healthy, never-attempted account IS purged despite two older stuck accounts
        // ahead of it in the queue; the stuck ones are (correctly) skipped this run.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=?", Integer.class, c.userId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=?", Integer.class, a.userId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE id=?", Integer.class, b.userId())).isEqualTo(1);

        // c is purged (user gone) — its retained consent won't be caught by @AfterEach's
        // email-join, so clear it here.
        jdbc.update("DELETE FROM consent_records WHERE user_id=?", c.userId());
    }
}
