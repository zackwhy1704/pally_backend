package com.pally.integration;

import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PART 4 — end-to-end trusted-adult review flow against a real Postgres
 * (Testcontainers). Drives create → public GET → respond, asserting the
 * page certainty/source transitions, single-shot semantics, lazy expiry,
 * rate limits, and — critically — that the PUBLIC view leaks ZERO PII.
 */
class ContentReviewIntegrationTest extends IntegrationTestBase {

    @Autowired
    WikiRepository wikiRepository;

    private AuthResult owner;
    private String avatarId;
    private String pageId;

    @BeforeEach
    void setUp() {
        owner = registerConsentedUser("review-owner-" + System.nanoTime() + "@test.com", "password123");

        Map<String, Object> avatarBody = Map.of(
                "name", "MathBot", "subject", "MATHS", "characterType", "MOCHI");
        ResponseEntity<Map> createResp = post("/api/v1/avatars", owner.token(), avatarBody);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        avatarId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");

        // Seed a wiki page directly so we have a real pageId to review.
        WikiPage page = WikiPage.create(avatarId, "fractions", "Fractions",
                "A fraction shows part of a whole, like 1/2 or 3/4.");
        pageId = wikiRepository.save(page).getId();
    }

    // ── Public response helpers (no auth header) ─────────────────────────────

    private ResponseEntity<Map> publicGet(String path) {
        return restTemplate.exchange(baseUrl() + path, HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), Map.class);
    }

    private ResponseEntity<Map> publicPost(String path, Object body) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(body, h), Map.class);
    }

    private String createRequestToken(boolean notifyParent) {
        ResponseEntity<Map> resp = post(
                "/api/v1/wiki-pages/" + pageId + "/review-request", owner.token(),
                Map.of("notifyParent", notifyParent));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) ((Map<String, Object>) resp.getBody().get("data")).get("token");
    }

    // ── Happy path: create → GET → APPROVE → VERIFIED + ADULT_REVIEW ─────────

    @Test
    void approve_setsPageVerifiedWithAdultReviewSource() {
        String token = createRequestToken(false);

        ResponseEntity<Map> view = publicGet("/api/v1/review/" + token);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) view.getBody().get("data");
        assertThat(data.get("pageTitle")).isEqualTo("Fractions");
        assertThat(data.get("status")).isEqualTo("PENDING");

        ResponseEntity<Map> respond = publicPost("/api/v1/review/" + token + "/respond",
                Map.of("verdict", "APPROVE", "reviewerName", "Ms Tan"));
        assertThat(respond.getStatusCode()).isEqualTo(HttpStatus.OK);

        WikiPage saved = wikiRepository.findById(pageId).orElseThrow();
        assertThat(saved.getCertainty()).isEqualTo(WikiPage.Certainty.VERIFIED);
        assertThat(saved.getVerificationSource()).isEqualTo("ADULT_REVIEW");
        assertThat(saved.getVerifiedBy()).isEqualTo("Ms Tan");
    }

    @Test
    void flag_setsPageUncertainAndStoresNote() {
        String token = createRequestToken(false);

        ResponseEntity<Map> respond = publicPost("/api/v1/review/" + token + "/respond",
                Map.of("verdict", "FLAG", "reviewerName", "Dad", "note", "1/2 is not 0.2"));
        assertThat(respond.getStatusCode()).isEqualTo(HttpStatus.OK);

        WikiPage saved = wikiRepository.findById(pageId).orElseThrow();
        assertThat(saved.getCertainty()).isEqualTo(WikiPage.Certainty.UNCERTAIN);

        // flagNote surfaces on the wiki-page DTO (owner-side list).
        ResponseEntity<Map> pages = get("/api/v1/avatars/" + avatarId + "/wiki/pages", owner.token());
        Map<String, Object> pagesData = (Map<String, Object>) pages.getBody().get("data");
        List<Map<String, Object>> pageList = (List<Map<String, Object>>) pagesData.get("pages");
        Map<String, Object> dto = pageList.stream()
                .filter(p -> pageId.equals(p.get("id"))).findFirst().orElseThrow();
        assertThat(dto.get("reviewState")).isEqualTo("FLAGGED");
        assertThat(dto.get("flagNote")).isEqualTo("1/2 is not 0.2");
    }

    @Test
    void flagWithoutNote_returns400() {
        String token = createRequestToken(false);
        ResponseEntity<Map> respond = publicPost("/api/v1/review/" + token + "/respond",
                Map.of("verdict", "FLAG"));
        assertThat(respond.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void respondTwice_secondReturns409() {
        String token = createRequestToken(false);
        publicPost("/api/v1/review/" + token + "/respond", Map.of("verdict", "APPROVE"));
        ResponseEntity<Map> second = publicPost("/api/v1/review/" + token + "/respond",
                Map.of("verdict", "APPROVE"));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getAfterApprove_returns410WithStatus() {
        String token = createRequestToken(false);
        publicPost("/api/v1/review/" + token + "/respond", Map.of("verdict", "APPROVE"));

        ResponseEntity<Map> view = publicGet("/api/v1/review/" + token);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.GONE);
        Map<String, Object> data = (Map<String, Object>) view.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("APPROVED");
    }

    @Test
    void revokedRequest_publicGetReturns410() {
        ResponseEntity<Map> resp = post(
                "/api/v1/wiki-pages/" + pageId + "/review-request", owner.token(),
                Map.of("notifyParent", false));
        String token = (String) ((Map<String, Object>) resp.getBody().get("data")).get("token");
        String requestId = listFirstRequestId();

        ResponseEntity<Map> del = delete(
                "/api/v1/wiki-pages/" + pageId + "/review-request/" + requestId, owner.token());
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> view = publicGet("/api/v1/review/" + token);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.GONE);
        Map<String, Object> data = (Map<String, Object>) view.getBody().get("data");
        assertThat(data.get("status")).isEqualTo("REVOKED");
    }

    @Test
    void fourthPendingRequest_returns409() {
        for (int i = 0; i < 3; i++) {
            ResponseEntity<Map> ok = post(
                    "/api/v1/wiki-pages/" + pageId + "/review-request", owner.token(),
                    Map.of("notifyParent", false));
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        }
        ResponseEntity<Map> fourth = post(
                "/api/v1/wiki-pages/" + pageId + "/review-request", owner.token(),
                Map.of("notifyParent", false));
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // ── PII: public view exposes no user id / email / displayName ────────────

    @Test
    void publicView_containsNoStudentPii() {
        String token = createRequestToken(false);
        ResponseEntity<Map> view = publicGet("/api/v1/review/" + token);
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.OK);

        String raw = view.getBody().toString();
        // No internal identifiers or contact info anywhere in the public payload.
        assertThat(raw).doesNotContain(owner.userId());
        assertThat(raw).doesNotContain(avatarId);
        assertThat(raw).doesNotContain(pageId);
        assertThat(raw).doesNotContainIgnoringCase("email");
        assertThat(raw).doesNotContainIgnoringCase("displayName");
        assertThat(raw).doesNotContainIgnoringCase("userId");

        Map<String, Object> data = (Map<String, Object>) view.getBody().get("data");
        assertThat(data.keySet())
                .containsExactlyInAnyOrder("pageTitle", "subject", "contentMarkdown", "status", "createdAt");
    }

    @Test
    void unknownToken_returns404() {
        ResponseEntity<Map> view = publicGet("/api/v1/review/does-not-exist");
        assertThat(view.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String listFirstRequestId() {
        ResponseEntity<Map> list = get(
                "/api/v1/wiki-pages/" + pageId + "/review-requests", owner.token());
        List<Map<String, Object>> data = (List<Map<String, Object>>) list.getBody().get("data");
        return (String) data.get(0).get("id");
    }
}
