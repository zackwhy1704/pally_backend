package com.pally.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDOR pin: {@code GET /avatars/{avatarId}/chat/history/full} previously never checked
 * that the requesting user owned {@code avatarId} — any authenticated user could read
 * another student's full raw chat history (including messages persisted through the
 * closed-book-refusal path, which bypass moderation) by supplying that student's
 * avatarId. Exercises the real HTTP + Spring Security + Testcontainers-Postgres stack,
 * end to end, the same way {@link SignupSecurityIntegrationTest} pins the EULA gate.
 */
class ChatHistoryIdorIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void stubs() {
        stubModerationPassthrough();
    }

    @Test
    void getFullHistory_otherUsersAvatar_rejectedAndNeverLeaksContent() {
        AuthResult victim = registerConsentedUser(
                "idor-victim-" + System.nanoTime() + "@test.com", "password123");
        String victimAvatarId = createAvatar(victim.token());
        seedMessage(victim.token(), victimAvatarId, "victim's private homework question");

        AuthResult attacker = registerConsentedUser(
                "idor-attacker-" + System.nanoTime() + "@test.com", "password123");

        ResponseEntity<Map> response = get(
                "/api/v1/avatars/" + victimAvatarId + "/chat/history/full", attacker.token());

        assertThat(response.getStatusCode())
                .as("a caller who does not own the avatar must be rejected, not served its chat")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().toString())
                .as("the victim's message content must never appear in the response body")
                .doesNotContain("victim's private homework question");
    }

    @Test
    void getFullHistory_ownAvatar_returnsOwnHistory() {
        AuthResult owner = registerConsentedUser(
                "idor-owner-" + System.nanoTime() + "@test.com", "password123");
        String avatarId = createAvatar(owner.token());
        seedMessage(owner.token(), avatarId, "my own question about fractions");

        ResponseEntity<Map> response = get(
                "/api/v1/avatars/" + avatarId + "/chat/history/full", owner.token());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().toString()).contains("my own question about fractions");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String createAvatar(String token) {
        ResponseEntity<Map> resp = post("/api/v1/avatars", token,
                Map.of("name", "MathMochi", "subject", "MATHS", "characterType", "MOCHI"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) ((Map<?, ?>) resp.getBody().get("data")).get("id");
    }

    /** Seeds one USER-role message into an avatar's chat via the real /chat/sync endpoint. */
    private void seedMessage(String token, String avatarId, String content) {
        Map<String, Object> message = Map.of(
                "id", java.util.UUID.randomUUID().toString(),
                "role", "USER",
                "content", content,
                "messageType", "text",
                "savedToBrain", false,
                "isPhotoMessage", false,
                "createdAt", Instant.now().toString());
        ResponseEntity<Map> resp = post(
                "/api/v1/avatars/" + avatarId + "/chat/sync", token,
                Map.of("messages", List.of(message)));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
