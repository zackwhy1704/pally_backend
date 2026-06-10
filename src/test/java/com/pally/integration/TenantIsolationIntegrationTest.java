package com.pally.integration;

import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies tenant isolation: owner-A cannot access org-B endpoints.
 * Seeds two organizations with distinct owners and asserts 403 on cross-org access.
 */
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OrganizationJpaRepository orgRepo;

    @Autowired
    private UserJpaRepository userRepo;

    private AuthResult ownerA;
    private AuthResult ownerB;
    private String orgAId;
    private String orgBId;

    @BeforeEach
    void seedOrgs() {
        ownerA = registerUser("tenant-owner-a-" + System.nanoTime() + "@test.com", "password123");
        ownerB = registerUser("tenant-owner-b-" + System.nanoTime() + "@test.com", "password123");

        // Create org-A owned by owner-A
        orgAId = IdGenerator.newId();
        OrganizationJpaEntity orgA = new OrganizationJpaEntity();
        orgA.setId(orgAId);
        orgA.setName("Centre A");
        orgA.setOwnerUserId(ownerA.userId());
        orgA.setSeatLimit(30);
        orgA.setCreatedAt(Instant.now());
        orgRepo.save(orgA);

        // Create org-B owned by owner-B
        orgBId = IdGenerator.newId();
        OrganizationJpaEntity orgB = new OrganizationJpaEntity();
        orgB.setId(orgBId);
        orgB.setName("Centre B");
        orgB.setOwnerUserId(ownerB.userId());
        orgB.setSeatLimit(30);
        orgB.setCreatedAt(Instant.now());
        orgRepo.save(orgB);
    }

    @Test
    void ownerA_cannotAccessOrgB_classes() {
        ResponseEntity<Map> response = get(
                "/api/v1/centre/organizations/" + orgBId + "/classes",
                ownerA.token());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void ownerA_cannotAccessOrgB_roster() {
        ResponseEntity<Map> response = get(
                "/api/v1/centre/organizations/" + orgBId + "/roster",
                ownerA.token());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void ownerA_cannotAccessOrgB_analytics() {
        ResponseEntity<Map> response = get(
                "/api/v1/centre/organizations/" + orgBId + "/analytics",
                ownerA.token());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void ownerA_cannotAccessOrgB_members() {
        ResponseEntity<Map> response = get(
                "/api/v1/centre/organizations/" + orgBId + "/members",
                ownerA.token());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void ownerA_cannotAccessOrgB_activity() {
        ResponseEntity<Map> response = get(
                "/api/v1/centre/organizations/" + orgBId + "/activity",
                ownerA.token());
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void ownerA_canAccessOwnOrg_classes() {
        ResponseEntity<Map> response = get(
                "/api/v1/centre/organizations/" + orgAId + "/classes",
                ownerA.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ownerB_canAccessOwnOrg_roster() {
        ResponseEntity<Map> response = get(
                "/api/v1/centre/organizations/" + orgBId + "/roster",
                ownerB.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void ownerA_cannotMintCodeForOrgB() {
        Map<String, Object> body = Map.of("cohortLabel", "P4", "seats", 10);
        ResponseEntity<Map> response = post(
                "/api/v1/centre/organizations/" + orgBId + "/enroll-code",
                ownerA.token(), body);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    void ownerA_cannotCreateClassInOrgB() {
        Map<String, Object> body = Map.of(
                "name", "Sneaky Class",
                "subject", "MATHS",
                "level", "P4");
        ResponseEntity<Map> response = post(
                "/api/v1/centre/organizations/" + orgBId + "/classes",
                ownerA.token(), body);
        assertThat(response.getStatusCode().value()).isEqualTo(403);
    }
}
