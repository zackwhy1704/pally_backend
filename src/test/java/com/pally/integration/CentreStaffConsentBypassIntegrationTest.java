package com.pally.integration;

import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test: centre operators (org owners and active staff) with no birth year
 * must never be blocked by any consent gate.
 *
 * <p>Invariants:
 * <ol>
 *   <li>Org owner, no birthYear → upload 201 (not 403 AGE_DECLARATION_REQUIRED)</li>
 *   <li>Invited staff, no birthYear → upload 201</li>
 *   <li>Plain user, no birthYear → upload 403 AGE_DECLARATION_REQUIRED (regression guard)</li>
 * </ol>
 */
class CentreStaffConsentBypassIntegrationTest extends IntegrationTestBase {

    @Autowired
    private OrgStaffJpaRepository orgStaffJpaRepository;

    @BeforeEach
    void stubs() {
        stubModerationPassthrough();
        stubRelevanceOnTopic();
        when(ocrPort.extractText(any(byte[].class), anyString()))
                .thenReturn("Algebra: solve for x when x + 2 = 5.");
        when(storageService.store(anyString(), any(), any(Long.class), anyString()))
                .thenReturn("test-key/" + System.nanoTime());
    }

    @Test
    void orgOwner_noBirthYear_canUpload_notBlockedByConsentGate() {
        AuthResult owner = registerUser(
                "owner-" + System.nanoTime() + "@test.com", "password123");

        // Onboard: sets org.ownerUserId, does NOT write an OrgStaff row.
        ResponseEntity<Map> onboard = post("/api/v1/centre/onboard", owner.token(),
                Map.of("centreName", "Bypass Test Centre"));
        assertThat(onboard.getStatusCode()).isEqualTo(HttpStatus.OK);

        String avatarId = createAvatar(owner.token());

        ResponseEntity<Map> upload = uploadFile(owner.token(), avatarId,
                "x + 2 = 5, solve for x.".getBytes(), "algebra.txt", "text/plain");
        assertThat(upload.getStatusCode())
                .as("Org owner with no birthYear must not be blocked by the consent gate")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void invitedStaff_noBirthYear_canUpload_notBlockedByConsentGate() {
        AuthResult owner = registerUser(
                "staff-owner-" + System.nanoTime() + "@test.com", "password123");
        ResponseEntity<Map> onboard = post("/api/v1/centre/onboard", owner.token(),
                Map.of("centreName", "Staff Centre"));
        assertThat(onboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        String orgId = (String) ((Map<?, ?>) onboard.getBody().get("data")).get("orgId");

        AuthResult staff = registerUser(
                "staff-" + System.nanoTime() + "@test.com", "password123");

        // Simulate invite acceptance: insert an OrgStaff row directly.
        OrgStaffJpaEntity staffRow = new OrgStaffJpaEntity();
        staffRow.setId(UUID.randomUUID().toString());
        staffRow.setOrgId(orgId);
        staffRow.setUserId(staff.userId());
        staffRow.setStatus(OrgStaffJpaEntity.STATUS_ACTIVE);
        orgStaffJpaRepository.save(staffRow);

        String avatarId = createAvatar(staff.token());

        ResponseEntity<Map> upload = uploadFile(staff.token(), avatarId,
                "Pythagoras: a² + b² = c².".getBytes(), "pythagoras.txt", "text/plain");
        assertThat(upload.getStatusCode())
                .as("Invited staff with no birthYear must not be blocked by the consent gate")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void plainUser_noBirthYear_isStillBlocked_defaultDenyHolds() {
        AuthResult user = registerUser(
                "plain-" + System.nanoTime() + "@test.com", "password123");
        String avatarId = createAvatar(user.token());

        ResponseEntity<Map> upload = uploadFile(user.token(), avatarId,
                "Statistics: mean, median, mode.".getBytes(), "stats.txt", "text/plain");
        assertThat(upload.getStatusCode())
                .as("Non-operator with no birthYear must still be blocked by the default-deny gate")
                .isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) upload.getBody().get("data");
        assertThat(data.get("reason")).isEqualTo("AGE_DECLARATION_REQUIRED");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String createAvatar(String token) {
        ResponseEntity<Map> resp = post("/api/v1/avatars", token,
                Map.of("name", "MathMochi", "subject", "MATHS", "characterType", "MOCHI"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) ((Map<?, ?>) resp.getBody().get("data")).get("id");
    }

    private ResponseEntity<Map> uploadFile(String token, String avatarId,
                                           byte[] bytes, String filename, String mime) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() { return filename; }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mime));
        body.add("file", new HttpEntity<>(resource, fileHeaders));

        return restTemplate.exchange(
                baseUrl() + "/api/v1/avatars/" + avatarId + "/files",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }
}
