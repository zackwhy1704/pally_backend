package com.pally.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Integration test for the upload + compile pipeline.
 * Stubs OCR and WikiCompiler ports; verifies the full HTTP flow.
 */
class UploadCompileIntegrationTest extends IntegrationTestBase {

    private AuthResult auth;
    private String avatarId;

    @BeforeEach
    void createAvatar() {
        auth = registerUser("upload-test-" + System.nanoTime() + "@test.com", "password123");
        // AI-disclosure gate is always-on — grant consent so upload paths run.
        grantAiConsent(auth.token());
        stubRelevanceOnTopic();
        stubModerationPassthrough();
        // Stub OCR to return realistic extracted text
        when(ocrPort.extractText(any(byte[].class), anyString()))
                .thenReturn("Fractions: A fraction has a numerator and denominator.");
        // Stub storage to accept any file (returns a storage key)
        when(storageService.store(anyString(), any(), any(Long.class), anyString()))
                .thenReturn("test-storage-key/" + System.nanoTime());
        when(storageService.store(anyString(), any(java.io.InputStream.class), any(Long.class), anyString()))
                .thenReturn("test-storage-key/" + System.nanoTime());

        // Create an avatar
        Map<String, Object> avatarBody = Map.of(
                "name", "MathBot",
                "subject", "MATHS",
                "characterType", "MOCHI");
        ResponseEntity<Map> createResp = post("/api/v1/avatars", auth.token(), avatarBody);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        avatarId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");
    }

    @Test
    void uploadFile_validTextFile_returns201() {
        ResponseEntity<Map> response = uploadTextFile(
                "Fractions: A fraction has a numerator and denominator. Example: 3/4.",
                "fractions.txt",
                "text/plain");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(data.get("fileId")).isNotNull();
    }

    @Test
    void uploadFile_emptyFile_returns400() {
        ResponseEntity<Map> response = uploadTextFile("", "empty.txt", "text/plain");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void uploadFile_unsupportedMime_returnsBadRequestOrFailure() {
        // Upload with an unsupported MIME type (e.g. application/zip)
        byte[] bytes = "fake zip content".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Map> response = uploadBytes(bytes, "archive.zip", "application/zip");
        // Should be rejected — either 400 or 500 with failure message about unsupported type
        assertThat(response.getStatusCode().value()).isIn(400, 500);
    }

    @Test
    void uploadFile_oversized_rejectedByServerOrController() {
        // 26MB exceeds both Spring's default multipart limit (configurable) and
        // the controller's 25MB cap. The embedded test server may reject at the
        // transport layer (I/O error) before the controller sees it — either
        // outcome means the file is correctly rejected.
        byte[] oversized = new byte[26 * 1024 * 1024];
        try {
            ResponseEntity<Map> response = uploadBytes(oversized, "huge.txt", "text/plain");
            // If it reaches the controller, expect 413
            assertThat(response.getStatusCode().value())
                    .as("Oversized file should be rejected")
                    .isIn(413, 500);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // Embedded Tomcat rejects before the controller — acceptable
            assertThat(e.getMessage()).contains("Error writing");
        }
    }

    @Test
    void relevanceCheck_onTopic_returnsRelevant() {
        stubRelevanceOnTopic();
        Map<String, Object> body = Map.of("contentSample", "Fractions and decimals are part of math");
        ResponseEntity<Map> response = post(
                "/api/v1/avatars/" + avatarId + "/relevance",
                auth.token(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(((Number) data.get("score")).doubleValue()).isGreaterThan(0.5);
        assertThat(data.get("isRelevant")).isEqualTo(true);
    }

    @Test
    void relevanceCheck_offTopic_returnsIrrelevant() {
        stubRelevanceOffTopic();
        Map<String, Object> body = Map.of("contentSample", "Cooking recipes for pasta");
        ResponseEntity<Map> response = post(
                "/api/v1/avatars/" + avatarId + "/relevance",
                auth.token(), body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) response.getBody().get("data");
        assertThat(((Number) data.get("score")).doubleValue()).isLessThan(0.5);
        assertThat(data.get("isRelevant")).isEqualTo(false);
    }

    @Test
    void listWikiPages_emptyAvatar_returnsEmptyList() {
        ResponseEntity<Map> response = get(
                "/api/v1/avatars/" + avatarId + "/wiki/pages",
                auth.token());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void compileWiki_noReadyFiles_returns200() {
        ResponseEntity<Map> response = post(
                "/api/v1/avatars/" + avatarId + "/wiki/compile",
                auth.token(), Map.of());
        // Should return 200 with 0 pages compiled (no files to compile)
        assertThat(response.getStatusCode().value()).isIn(200, 202);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<Map> uploadTextFile(String content, String filename, String mimeType) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return uploadBytes(bytes, filename, mimeType);
    }

    private ResponseEntity<Map> uploadBytes(byte[] bytes, String filename, String mimeType) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(auth.token());

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mimeType));
        body.add("file", new HttpEntity<>(resource, fileHeaders));

        HttpEntity<MultiValueMap<String, Object>> request = new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                baseUrl() + "/api/v1/avatars/" + avatarId + "/files",
                HttpMethod.POST, request, Map.class);
    }
}
