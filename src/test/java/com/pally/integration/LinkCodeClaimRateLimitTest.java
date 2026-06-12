package com.pally.integration;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers the link-a-grown-up hardening: the 15-minute code TTL and the
 * per-parent claim throttle.
 *
 * <ul>
 *   <li>Issued codes report a ~900s ({@code 15min}) TTL.</li>
 *   <li>A code older than 15 minutes is rejected as expired.</li>
 *   <li>Failed claims accumulate; the 6th within the hour returns 429.</li>
 *   <li>A successful claim resets the counter.</li>
 * </ul>
 *
 * <p>The rate limiter keys on the authenticated parent's userId, so each test
 * registers a fresh parent to avoid cross-test counter bleed in the shared
 * in-memory limiter.
 */
class LinkCodeClaimRateLimitTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userRepo;

    private AuthResult freshParent() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthResult parent = registerUser("rl-parent-" + suffix + "@test.com", "parentPass1");
        userRepo.findById(parent.userId()).ifPresent(u -> {
            u.setAccountType("PARENT");
            userRepo.save(u);
        });
        return parent;
    }

    private String issueChildCode() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthResult child = registerUser("rl-child-" + suffix + "@test.com", "childPass1");
        ResponseEntity<Map> link = post("/api/v1/account/link-code", child.token(), null);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) link.getBody().get("data");
        return (String) data.get("code");
    }

    @Test
    @SuppressWarnings("unchecked")
    void issuedCode_reportsFifteenMinuteTtl() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthResult child = registerUser("rl-ttl-" + suffix + "@test.com", "childPass1");
        ResponseEntity<Map> link = post("/api/v1/account/link-code", child.token(), null);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> data = (Map<String, Object>) link.getBody().get("data");
        long ttlSeconds = ((Number) data.get("ttlSeconds")).longValue();
        // 15 minutes = 900 seconds. Exact, since CODE_TTL is a fixed Duration.
        assertThat(ttlSeconds).isEqualTo(900);
    }

    @Test
    void codeOlderThanFifteenMinutes_isRejectedAsExpired() {
        AuthResult parent = freshParent();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        AuthResult child = registerUser("rl-exp-" + suffix + "@test.com", "childPass1");

        ResponseEntity<Map> link = post("/api/v1/account/link-code", child.token(), null);
        assertThat(link.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) link.getBody().get("data");
        String code = (String) data.get("code");

        // Force the code to have expired (simulate >15 min having passed).
        UserJpaEntity childEntity = userRepo.findByLinkCode(code).orElseThrow();
        childEntity.setLinkCodeExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        userRepo.save(childEntity);

        ResponseEntity<Map> claim = post("/api/v1/account/claim", parent.token(),
                Map.of("code", code));
        // Controller returns 410 GONE for an expired code.
        assertThat(claim.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }

    @Test
    void sixthFailedClaimWithinHour_returns429() {
        AuthResult parent = freshParent();

        // 5 failed claims with bogus codes — all allowed (404 invalid code),
        // each recording a hit toward the limit.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> bad = post("/api/v1/account/claim", parent.token(),
                    Map.of("code", "ZZZZZ" + i));
            assertThat(bad.getStatusCode())
                    .as("failed claim #%d should be a normal failure, not throttled", i + 1)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        // 6th attempt is throttled before the code is even checked.
        ResponseEntity<Map> sixth = post("/api/v1/account/claim", parent.token(),
                Map.of("code", "ZZZZZX"));
        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void successfulClaim_resetsTheFailureCounter() {
        AuthResult parent = freshParent();

        // Burn 4 failed attempts (still under the limit of 5).
        for (int i = 0; i < 4; i++) {
            ResponseEntity<Map> bad = post("/api/v1/account/claim", parent.token(),
                    Map.of("code", "WRONG" + i));
            assertThat(bad.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        // A valid claim succeeds AND clears the counter.
        String goodCode = issueChildCode();
        ResponseEntity<Map> good = post("/api/v1/account/claim", parent.token(),
                Map.of("code", goodCode));
        assertThat(good.getStatusCode()).isEqualTo(HttpStatus.OK);

        // After the reset, the parent gets a full fresh allowance of 5 failures
        // before being throttled — proving the success wiped the prior 4 hits.
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> bad = post("/api/v1/account/claim", parent.token(),
                    Map.of("code", "AGAIN" + i));
            assertThat(bad.getStatusCode())
                    .as("post-reset failed claim #%d must not be throttled", i + 1)
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        // Only the 6th post-reset attempt trips the limiter.
        ResponseEntity<Map> throttled = post("/api/v1/account/claim", parent.token(),
                Map.of("code", "AGAINX"));
        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }
}
