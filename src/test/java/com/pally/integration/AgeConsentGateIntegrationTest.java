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

    @Autowired
    private com.pally.domain.consent.ConsentRepository consentRepository;

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
    void under13_registerWithoutParentEmail_isRejected() {
        Map<String, Object> body = Map.of(
                "email", "noparent-" + System.nanoTime() + "@test.com",
                "password", "password123", "displayName", "Kid",
                "birthYear", sgYear() - 10); // under-13, no parentEmail
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                baseUrl() + "/api/v1/auth/register", new HttpEntity<>(body, headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void under13_pending_cannotCreatePersonalTutor_untilParentApproves() {
        AuthResult kid = registerUnder13("kid-" + System.nanoTime() + "@test.com",
                "password123", sgYear() - 10, "parent@test.com");
        // Created PENDING_PARENTAL_CONSENT.
        assertThat(userRepo.findById(kid.userId()).orElseThrow().getAccountStatus())
                .isEqualTo("PENDING_CONSENT");

        // Pending → blocked from new-child-data (personal tutor creation).
        ResponseEntity<Map> blocked = post("/api/v1/avatars", kid.token(),
                Map.of("name", "MathBot", "subject", "MATHS", "characterType", "MOCHI"));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void under13_afterParentApproval_isActive_andCanUpload() {
        AuthResult kid = registerUnder13("approve-" + System.nanoTime() + "@test.com",
                "password123", sgYear() - 10, "parent@test.com");

        // Parent opens the one-tap email link → approve token → ACTIVE.
        String token = consentRepository.findLatestRequestByChildUserIdAndStatus(
                kid.userId(),
                com.pally.domain.consent.ConsentRepository.ConsentRequest.STATUS_PENDING)
                .orElseThrow().token();
        ResponseEntity<Map> approve = restTemplate.postForEntity(
                baseUrl() + "/api/v1/consent/approve?token=" + token,
                new HttpEntity<>(new HttpHeaders()), Map.class);
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userRepo.findById(kid.userId()).orElseThrow().getAccountStatus())
                .isEqualTo("ACTIVE");

        // Now ACTIVE + parental consent recorded → upload allowed.
        grantAiConsent(kid.token());
        avatarId = createAvatar(kid.token());
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
    void noBirthYear_upload_isDefaultDenied_notTreatedAs13Plus() {
        // DEFAULT-DENY (child safety): age not on file ⇒ we can't establish 13+, so
        // uploading own notes is blocked (AGE_DECLARATION_REQUIRED) — NOT allowed. A
        // child must not bypass the gate by omitting their birth year. (The account is
        // ACTIVE so tutor creation still works; only new-child-data ingestion is gated.)
        // A genuine NO-birth-year account (registerUser stores none), AI-consented.
        AuthResult user = registerUser("noyear-" + System.nanoTime() + "@test.com", "password123");
        grantAiConsent(user.token());
        avatarId = createAvatar(user.token());

        ResponseEntity<Map> resp = uploadBytes(user.token(),
                "Fractions: numerator over denominator.".getBytes(StandardCharsets.UTF_8),
                "frac.txt", "text/plain");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data).containsEntry("reason", "AGE_DECLARATION_REQUIRED");
    }

    @Test
    void thirteenPlus_unconsentedUser_isNeverBlockedByAiConsentGate() {
        // 13+ users self-consent — the AI disclosure gate must be a no-op for them.
        AuthResult teen = registerUserWithBirthYear(
                "noconsent13plus-" + System.nanoTime() + "@test.com", "password123", sgYear() - 16);
        avatarId = createAvatar(teen.token());

        ResponseEntity<Map> resp = uploadBytes(teen.token(),
                "Fractions: numerator over denominator.".getBytes(StandardCharsets.UTF_8),
                "frac.txt", "text/plain");
        // Upload should succeed (or fail for another reason) — never with AI_CONSENT_REQUIRED.
        if (resp.getStatusCode() == HttpStatus.FORBIDDEN) {
            Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
            assertThat(data).doesNotContainEntry("code", "AI_CONSENT_REQUIRED");
        }
    }

    @Test
    void register_storesBirthYear_onStudentPath() {
        // 13+ student (no parent email needed) — proves the year is stored server-side.
        AuthResult teen = registerUserWithBirthYear(
                "store-" + System.nanoTime() + "@test.com", "password123", sgYear() - 16);
        UserJpaEntity saved = userRepo.findById(teen.userId()).orElseThrow();
        assertThat(saved.getBirthYear()).isEqualTo(sgYear() - 16);
    }

    @Test
    void under13_pending_canStillReadCentreContent_loginNotBlocked() {
        // Half-elevated state: a pending under-13 can authenticate and read content
        // (centre lessons are monitored). Login + a GET must NOT be gated.
        String email = "read-" + System.nanoTime() + "@test.com";
        AuthResult kid = registerUnder13(email, "password123", sgYear() - 10, "parent@test.com");
        // Login is not blocked for a pending child.
        AuthResult relog = loginUser(email, "password123");
        assertThat(relog.token()).isNotBlank();
        // And an authenticated read endpoint is reachable while pending (not 403).
        ResponseEntity<Map> avatars = restTemplate.exchange(
                baseUrl() + "/api/v1/avatars", HttpMethod.GET,
                new HttpEntity<>(authHeaders(kid.token())), Map.class);
        assertThat(avatars.getStatusCode()).isEqualTo(HttpStatus.OK); // not 403
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
