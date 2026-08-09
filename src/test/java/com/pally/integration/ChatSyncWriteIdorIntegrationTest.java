package com.pally.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDOR pin (write side): {@code POST /avatars/{avatarId}/chat/sync} previously never
 * checked that the requesting user owned {@code avatarId} — any authenticated user
 * could plant fabricated messages into another student's avatar's chat history, or
 * mutate the feedback/saved-to-brain flag on an existing message by id, regardless of
 * who owned it. Sibling of the read-side {@link ChatHistoryIdorIntegrationTest} fix —
 * same real HTTP + Spring Security + Testcontainers-Postgres stack.
 */
class ChatSyncWriteIdorIntegrationTest extends IntegrationTestBase {

    @BeforeEach
    void stubs() {
        stubModerationPassthrough();
    }

    @Test
    void syncAgainstAnotherUsersAvatar_rejectsEntireRequest_victimChatUnchanged() {
        AuthResult victim = registerConsentedUser(
                "sync-idor-victim-" + System.nanoTime() + "@test.com", "password123");
        String victimAvatarId = createAvatar(victim.token());
        String victimMessageId = seedMessage(victim.token(), victimAvatarId,
                "victim's real question", "HELPFUL", false);

        AuthResult attacker = registerConsentedUser(
                "sync-idor-attacker-" + System.nanoTime() + "@test.com", "password123");

        // (a) attempt to mutate the victim's existing message's feedback/saved-to-brain,
        // (b) attempt to plant a brand-new fabricated message — bundled in one request
        // against the victim's avatarId, exactly as a real attack payload would.
        Map<String, Object> mutateExisting = Map.of(
                "id", victimMessageId,
                "role", "USER",
                "content", "victim's real question",
                "messageType", "text",
                "feedbackType", "WRONG",
                "savedToBrain", true,
                "isPhotoMessage", false,
                "createdAt", Instant.now().toString());
        Map<String, Object> plantedMessage = Map.of(
                "id", UUID.randomUUID().toString(),
                "role", "ASSISTANT",
                "content", "fabricated content planted by an attacker",
                "messageType", "text",
                "savedToBrain", false,
                "isPhotoMessage", false,
                "createdAt", Instant.now().toString());

        ResponseEntity<Map> response = post(
                "/api/v1/avatars/" + victimAvatarId + "/chat/sync", attacker.token(),
                Map.of("messages", List.of(mutateExisting, plantedMessage)));

        assertThat(response.getStatusCode())
                .as("a caller who does not own the avatar must be rejected outright")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // Verify via the VICTIM's own eyes that nothing changed: original feedback
        // untouched, no planted message appended.
        ResponseEntity<Map> victimView = get(
                "/api/v1/avatars/" + victimAvatarId + "/chat/history/full", victim.token());
        assertThat(victimView.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = victimView.getBody().toString();
        assertThat(body)
                .as("the attacker's fabricated message must never appear in the victim's chat")
                .doesNotContain("fabricated content planted by an attacker");
        assertThat(body)
                .as("the original feedback type must be unchanged (still HELPFUL, not WRONG)")
                .contains("HELPFUL")
                .doesNotContain("WRONG");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String createAvatar(String token) {
        ResponseEntity<Map> resp = post("/api/v1/avatars", token,
                Map.of("name", "MathMochi", "subject", "MATHS", "characterType", "MOCHI"));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) ((Map<?, ?>) resp.getBody().get("data")).get("id");
    }

    /** Seeds one message via the real /chat/sync endpoint and returns its id. */
    private String seedMessage(String token, String avatarId, String content,
                                String feedbackType, boolean savedToBrain) {
        String id = UUID.randomUUID().toString();
        Map<String, Object> message = new java.util.HashMap<>();
        message.put("id", id);
        message.put("role", "USER");
        message.put("content", content);
        message.put("messageType", "text");
        message.put("feedbackType", feedbackType);
        message.put("savedToBrain", savedToBrain);
        message.put("isPhotoMessage", false);
        message.put("createdAt", Instant.now().toString());
        ResponseEntity<Map> resp = post(
                "/api/v1/avatars/" + avatarId + "/chat/sync", token,
                Map.of("messages", List.of(message)));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return id;
    }
}
