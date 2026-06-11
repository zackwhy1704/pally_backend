package com.pally.integration;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the parent-child experience endpoints.
 * Registers a parent (with role=parent), registers a child, links them
 * via the claim flow, then exercises every ParentChildController endpoint.
 */
class ParentChildIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userRepo;

    private String parentToken;
    private String parentId;
    private String childToken;
    private String childId;

    @BeforeEach
    void linkParentAndChild(TestInfo testInfo) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        // Register parent — upgrade to PARENT account type
        AuthResult parentAuth = registerUser("parent-" + suffix + "@test.com", "parentPass1");
        parentToken = parentAuth.token();
        parentId = parentAuth.userId();
        userRepo.findById(parentId).ifPresent(u -> {
            u.setAccountType("PARENT");
            userRepo.save(u);
        });

        // Register child
        AuthResult childAuth = registerUser("child-" + suffix + "@test.com", "childPass1");
        childToken = childAuth.token();
        childId = childAuth.userId();

        // Child generates link code
        ResponseEntity<Map> linkResponse = post("/api/v1/account/link-code", childToken, null);
        assertThat(linkResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> linkData = (Map<String, Object>) linkResponse.getBody().get("data");
        String code = (String) linkData.get("code");

        // Parent claims child
        ResponseEntity<Map> claimResponse = post("/api/v1/account/claim", parentToken,
                Map.of("code", code));
        assertThat(claimResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void getDashboard_returnsChildStats() {
        ResponseEntity<Map> resp = get(
                "/api/v1/parent/children/" + childId + "/dashboard", parentToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data).containsKey("sessionsThisWeek");
        assertThat(data).containsKey("minutesThisWeek");
        assertThat(data).containsKey("level");
        assertThat(data).containsKey("streakDays");
    }

    @Test
    void getReports_returnsChildReports() {
        ResponseEntity<Map> resp = get(
                "/api/v1/parent/children/" + childId + "/reports", parentToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data).containsKey("reports");
    }

    @Test
    void assignRevision_createsAssignment() {
        ResponseEntity<Map> resp = post(
                "/api/v1/parent/children/" + childId + "/assign-revision",
                parentToken,
                Map.of("moduleIds", List.of("mod-1", "mod-2"),
                        "dueDate", "2026-07-01T00:00:00Z",
                        "title", "Review fractions"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data).containsKey("assignmentId");
        assertThat(data.get("title")).isEqualTo("Review fractions");
    }

    @Test
    void awardStars_addsStarsWithDailyCap() {
        ResponseEntity<Map> resp = post(
                "/api/v1/parent/children/" + childId + "/award-stars",
                parentToken,
                Map.of("amount", 50, "note", "Good job!"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(((Number) data.get("awarded")).intValue()).isEqualTo(50);
        assertThat(((Number) data.get("newBalance")).intValue()).isGreaterThanOrEqualTo(50);
    }

    @Test
    void awardStars_exceedingPerAwardCap_returns400() {
        ResponseEntity<Map> resp = post(
                "/api/v1/parent/children/" + childId + "/award-stars",
                parentToken,
                Map.of("amount", 150));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void putAndGetGoal_roundTrips() {
        // PUT
        ResponseEntity<Map> putResp = restTemplate.exchange(
                baseUrl() + "/api/v1/parent/children/" + childId + "/goal",
                org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        Map.of("weeklyMinutes", 60, "weeklyModules", 3),
                        authHeaders(parentToken)),
                Map.class);
        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // GET
        ResponseEntity<Map> getResp = get(
                "/api/v1/parent/children/" + childId + "/goal", parentToken);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) getResp.getBody().get("data");
        assertThat(((Number) data.get("weeklyMinutes")).intValue()).isEqualTo(60);
        assertThat(((Number) data.get("weeklyModules")).intValue()).isEqualTo(3);
    }

    @Test
    void assertIsChild_blocksUnrelatedParent() {
        // Register a different parent
        AuthResult otherParent = registerUser("other-parent-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com", "otherPass1");
        userRepo.findById(otherParent.userId()).ifPresent(u -> {
            u.setAccountType("PARENT");
            userRepo.save(u);
        });

        ResponseEntity<Map> resp = get(
                "/api/v1/parent/children/" + childId + "/dashboard",
                otherParent.token());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void getConsent_returnsNoConsentInitially() {
        ResponseEntity<Map> resp = get(
                "/api/v1/parent/children/" + childId + "/consent", parentToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("hasConsent")).isEqualTo(false);
    }

    @Test
    void fcmToken_savesSuccessfully() {
        ResponseEntity<Map> resp = post("/api/v1/account/fcm-token", childToken,
                Map.of("token", "fcm-test-token-12345"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        UserJpaEntity child = userRepo.findById(childId).orElseThrow();
        assertThat(child.getFcmToken()).isEqualTo("fcm-test-token-12345");
    }

    @Test
    void familyAcceptConsent_createsRecord() {
        // Child accepts parent visibility
        ResponseEntity<Map> resp = post("/api/v1/consent/family-accept", childToken,
                Map.of("parentId", parentId));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("FAMILY_CONSENT_GRANTED");

        // Verify consent shows up for parent
        ResponseEntity<Map> consentResp = get(
                "/api/v1/parent/children/" + childId + "/consent", parentToken);
        assertThat(consentResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> consentData = (Map<String, Object>) consentResp.getBody().get("data");
        assertThat(consentData.get("hasConsent")).isEqualTo(true);
    }

    @Test
    void listModules_returnsEmptyForNewChild() {
        ResponseEntity<Map> resp = get(
                "/api/v1/parent/children/" + childId + "/modules", parentToken);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Object> data = (List<Object>) resp.getBody().get("data");
        assertThat(data).isEmpty();
    }
}
