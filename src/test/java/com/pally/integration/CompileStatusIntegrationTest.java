package com.pally.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the compile status endpoint.
 * Verifies that unknown avatar compile status returns a friendly response
 * (not 500) and that the endpoint is reachable with valid auth.
 */
class CompileStatusIntegrationTest extends IntegrationTestBase {

    @Test
    void compileStatus_unknownAvatar_returnsNoneState() {
        AuthResult auth = registerUser("compile-status-" + System.nanoTime() + "@test.com", "password123");

        // Create an avatar first so the path is valid
        Map<String, Object> avatarBody = Map.of(
                "name", "TestMochi",
                "subject", "MATHS",
                "characterType", "MOCHI");
        ResponseEntity<Map> createResp = post("/api/v1/avatars", auth.token(), avatarBody);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String avatarId = (String) ((Map<String, Object>) createResp.getBody().get("data")).get("id");

        // Query compile status — no compile has been run, should return NONE
        ResponseEntity<Map> statusResp = get(
                "/api/v1/avatars/" + avatarId + "/wiki/compile/status",
                auth.token());
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) statusResp.getBody().get("data");
        assertThat(data.get("state")).isEqualTo("NONE");
    }

    @Test
    void compileStatus_nonExistentAvatar_returnsOkWithNone() {
        // The endpoint doesn't check avatar ownership in its current implementation;
        // it checks the in-memory CompileJobStore which is keyed by avatarId.
        AuthResult auth = registerUser("compile-status-noavatar-" + System.nanoTime() + "@test.com", "password123");

        // Create an avatar to have a valid one, then query with a fake avatarId
        Map<String, Object> avatarBody = Map.of(
                "name", "TestMochi",
                "subject", "MATHS",
                "characterType", "MOCHI");
        post("/api/v1/avatars", auth.token(), avatarBody);

        ResponseEntity<Map> statusResp = get(
                "/api/v1/avatars/nonexistent-avatar-id/wiki/compile/status",
                auth.token());
        // The endpoint returns 200 with state=NONE for unknown avatarIds
        // (it queries the in-memory job store, not the DB)
        assertThat(statusResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> data = (Map<String, Object>) statusResp.getBody().get("data");
        assertThat(data.get("state")).isEqualTo("NONE");
    }
}
