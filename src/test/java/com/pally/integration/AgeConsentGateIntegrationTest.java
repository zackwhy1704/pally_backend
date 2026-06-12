package com.pally.integration;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
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

import java.nio.charset.StandardCharsets;
import java.time.Year;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * End-to-end coverage of the PDPC 2024 age-conditional consent gate over HTTP.
 *
 * <p>Verifies the two response codes the mobile client routes on:
 * <ul>
 *   <li>An under-13 student (AI-consented) is BLOCKED with 403 PARENT_LINK_REQUIRED
 *       on upload until a parent is linked, then ALLOWED once linked.</li>
 *   <li>A 13+ student (AI-consented) is never gated by PARENT_LINK_REQUIRED.</li>
 *   <li>An un-consented user hits 403 AI_CONSENT_REQUIRED first (always-on gate).</li>
 * </ul>
 */
class AgeConsentGateIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userRepo;

    private String avatarId;

    @BeforeEach
    void stubs() {
        stubRelevanceOnTopic();
        stubModerationPassthrough();
        when(ocrPort.extractText(any(byte[].class), anyString()))
                .thenReturn("Fractions: a fraction has a numerator and denominator.");
        when(storageService.store(anyString(), any(), any(Long.class), anyString()))
                .thenReturn("test-key/" + System.nanoTime());
        when(storageService.store(anyString(), any(java.io.InputStream.class), any(Long.class), anyString()))
                .thenReturn("test-key/" + System.nanoTime());
    }

    private int sgYear() {
        return Year.now(ZoneId.of("Asia/Singapore")).getValue();
    }

    private String createAvatar(String token) {
        ResponseEntity<Map> resp = post("/api/v1/avatars", token, Map.of(
                "name", "MathBot", "subject", "MATHS", "characterType", "MOCHI"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) ((Map<String, Object>) resp.getBody().get("data")).get("id");
    }

    @Test
    void under13_noParent_upload_blockedWithParentLinkRequired_thenAllowedAfterLink() {
        AuthResult kid = registerUserWithBirthYear(
                "kid-" + System.nanoTime() + "@test.com", "password123", sgYear() - 10);
        grantAiConsent(kid.token());
        avatarId = createAvatar(kid.token());

        // Under-13, no parent linked → blocked.
        ResponseEntity<Map> blocked = uploadBytes(kid.token(),
                "Fractions: numerator over denominator.".getBytes(StandardCharsets.UTF_8),
                "frac.txt", "text/plain");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> data = (Map<String, Object>) blocked.getBody().get("data");
        assertThat(data).containsEntry("code", "PARENT_LINK_REQUIRED");

        // Link a real parent account (FK-constrained). The claim flow records
        // consent + sets parentId; we set parentId directly to the parent's id.
        AuthResult parent = registerUser("parent-" + System.nanoTime() + "@test.com", "password123");
        UserJpaEntity child = userRepo.findById(kid.userId()).orElseThrow();
        child.setParentId(parent.userId());
        userRepo.save(child);

        // Now allowed.
        ResponseEntity<Map> allowed = uploadBytes(kid.token(),
                "Fractions: numerator over denominator.".getBytes(StandardCharsets.UTF_8),
                "frac.txt", "text/plain");
        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void thirteenPlus_consented_neverHitsParentLinkRequired() {
        AuthResult teen = registerUserWithBirthYear(
                "teen-" + System.nanoTime() + "@test.com", "password123", sgYear() - 16);
        grantAiConsent(teen.token());
        avatarId = createAvatar(teen.token());

        ResponseEntity<Map> resp = uploadBytes(teen.token(),
                "Fractions: numerator over denominator.".getBytes(StandardCharsets.UTF_8),
                "frac.txt", "text/plain");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void noBirthYear_consented_treatedAs13Plus_allowed() {
        AuthResult user = registerConsentedUser(
                "noyear-" + System.nanoTime() + "@test.com", "password123");
        avatarId = createAvatar(user.token());

        ResponseEntity<Map> resp = uploadBytes(user.token(),
                "Fractions: numerator over denominator.".getBytes(StandardCharsets.UTF_8),
                "frac.txt", "text/plain");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void unconsentedUser_hitsAiConsentRequiredFirst() {
        AuthResult user = registerUser(
                "noconsent-" + System.nanoTime() + "@test.com", "password123");
        avatarId = createAvatar(user.token());

        ResponseEntity<Map> resp = uploadBytes(user.token(),
                "Fractions: numerator over denominator.".getBytes(StandardCharsets.UTF_8),
                "frac.txt", "text/plain");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data).containsEntry("code", "AI_CONSENT_REQUIRED");
    }

    @Test
    void register_storesBirthYear_onStudentPath() {
        AuthResult kid = registerUserWithBirthYear(
                "store-" + System.nanoTime() + "@test.com", "password123", sgYear() - 10);
        UserJpaEntity saved = userRepo.findById(kid.userId()).orElseThrow();
        assertThat(saved.getBirthYear()).isEqualTo(sgYear() - 10);
    }

    @Test
    void register_futureBirthYear_rejectedAsBadRequest() {
        Map<String, Object> body = Map.of(
                "email", "future-" + System.nanoTime() + "@test.com",
                "password", "password123",
                "displayName", "Test",
                "birthYear", sgYear() + 5);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(body, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void register_tooLowBirthYear_rejectedByValidation() {
        Map<String, Object> body = Map.of(
                "email", "low-" + System.nanoTime() + "@test.com",
                "password", "password123",
                "displayName", "Test",
                "birthYear", 1900);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register",
                new HttpEntity<>(body, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map> uploadBytes(String token, byte[] bytes, String filename, String mime) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mime));
        body.add("file", new HttpEntity<>(resource, fileHeaders));

        return restTemplate.exchange(
                baseUrl() + "/api/v1/avatars/" + avatarId + "/files",
                HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
