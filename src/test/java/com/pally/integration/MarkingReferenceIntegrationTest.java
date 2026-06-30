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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the teacher marking-reference endpoints — the grounding a
 * teacher uploads so the AI homework draft marks against their own standard.
 *
 * <p>Proves the contract end-to-end against Postgres: an upload returns 201 with
 * the indexed DTO shape, the list reflects it, a delete clears it, a non-staff
 * user is refused (403), and a reference can't be reached through a sibling
 * class's path (cross-class IDOR → 404).
 */
class MarkingReferenceIntegrationTest extends IntegrationTestBase {

    private AuthResult owner;
    private String orgId;
    private String classId;

    @BeforeEach
    void seedCentreAndClass() {
        owner = registerUser("marking-owner-" + System.nanoTime() + "@test.com", "password123");

        ResponseEntity<Map> onboard = post("/api/v1/centre/onboard", owner.token(),
                Map.of("centreName", "Marking Centre"));
        assertThat(onboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        orgId = (String) ((Map<?, ?>) onboard.getBody().get("data")).get("orgId");

        classId = createClass("P5 Math", "MATHS");
    }

    @Test
    void upload_returns201WithIndexedDtoShape() {
        ResponseEntity<Map> resp = uploadReference(owner.token(), classId,
                "MARKED_PAPER", "2023 SA2 A-grade", "full working shown",
                "Q1 (2 marks): 1 for method, 1 for answer.".getBytes(),
                "exemplar.txt", "text/plain");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data.get("id")).isNotNull();
        assertThat(data.get("classId")).isEqualTo(classId);
        assertThat(data.get("kind")).isEqualTo("MARKED_PAPER");
        assertThat(data.get("title")).isEqualTo("2023 SA2 A-grade");
        assertThat(data.get("note")).isEqualTo("full working shown");
        // Plain-text extracts inline, so the teacher sees a non-zero indexed-char count.
        assertThat(((Number) data.get("extractedChars")).intValue()).isGreaterThan(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files = (List<Map<String, Object>>) data.get("files");
        assertThat(files).hasSize(1);
        assertThat(files.get(0).get("name")).isEqualTo("exemplar.txt");
        // The raw extracted text is NEVER exposed in the DTO — only the count.
        assertThat(data).doesNotContainKey("extractedText");
    }

    @Test
    void list_reflectsUploadedReference() {
        uploadReference(owner.token(), classId, "RUBRIC", "Mark scheme", null,
                "Award marks per the scheme.".getBytes(), "rubric.txt", "text/plain");

        ResponseEntity<Map> resp = get(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/marking-references",
                owner.token());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) resp.getBody().get("data");
        assertThat(data).hasSize(1);
        assertThat(data.get(0).get("title")).isEqualTo("Mark scheme");
        assertThat(data.get(0).get("kind")).isEqualTo("RUBRIC");
    }

    @Test
    void delete_removesReference() {
        ResponseEntity<Map> created = uploadReference(owner.token(), classId,
                "GUIDELINE", "What to upload", null,
                "Upload your past marked papers.".getBytes(), "guide.txt", "text/plain");
        @SuppressWarnings("unchecked")
        String refId = (String) ((Map<String, Object>) created.getBody().get("data")).get("id");

        ResponseEntity<Map> del = delete(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId
                        + "/marking-references/" + refId, owner.token());
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> list = get(
                "/api/v1/centre/organizations/" + orgId + "/classes/" + classId + "/marking-references",
                owner.token());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) list.getBody().get("data");
        assertThat(data).isEmpty();
    }

    @Test
    void upload_byNonStaffUser_isForbidden() {
        AuthResult outsider = registerUser(
                "outsider-" + System.nanoTime() + "@test.com", "password123");

        ResponseEntity<Map> resp = uploadReference(outsider.token(), classId,
                "MARKED_PAPER", "Sneaky", null,
                "should be blocked".getBytes(), "x.txt", "text/plain");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void file_throughSiblingClassPath_isNotFound_crossClassIdorGuard() {
        // Reference belongs to classId.
        ResponseEntity<Map> created = uploadReference(owner.token(), classId,
                "MARKED_PAPER", "Class A paper", null,
                "class A content".getBytes(), "a.txt", "text/plain");
        @SuppressWarnings("unchecked")
        String refId = (String) ((Map<String, Object>) created.getBody().get("data")).get("id");

        // A second class in the SAME org — passes the org/class check but must not
        // expose another class's reference.
        String otherClassId = createClass("P5 Science", "SCIENCE");

        ResponseEntity<byte[]> resp = restTemplate.exchange(
                baseUrl() + "/api/v1/centre/organizations/" + orgId + "/classes/" + otherClassId
                        + "/marking-references/" + refId + "/files/0",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(owner.token())),
                byte[].class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String createClass(String name, String subject) {
        ResponseEntity<Map> resp = post(
                "/api/v1/centre/organizations/" + orgId + "/classes",
                owner.token(), Map.of("name", name, "subject", subject, "level", "P5"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) ((Map<?, ?>) resp.getBody().get("data")).get("id");
    }

    private ResponseEntity<Map> uploadReference(String token, String classId, String kind,
                                                String title, String note,
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
        body.add("files", new HttpEntity<>(resource, fileHeaders));
        body.add("kind", kind);
        body.add("title", title);
        if (note != null) {
            body.add("note", note);
        }

        return restTemplate.exchange(
                baseUrl() + "/api/v1/centre/organizations/" + orgId + "/classes/" + classId
                        + "/marking-references",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }
}
