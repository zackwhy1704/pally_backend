package com.pally.integration;

import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/// End-to-end proof that real study durations flow into the weekly minutes
/// stat. Before the fix, every activity site logged durationSeconds=0, so
/// {@code GET /api/v1/progress} always returned all-zero weekMinutes. Now the
/// reading ping reports real time and the chart becomes truthful.
class StudyMinutesIntegrationTest extends IntegrationTestBase {

    @SuppressWarnings("unchecked")
    @Test
    void readingPing_makesWeekMinutesNonzero() {
        String email = "minutes-" + UUID.randomUUID() + "@test.com";
        AuthResult auth = registerUser(email, "password123");

        // Create an avatar to attribute the reading time to.
        ResponseEntity<Map> created = post("/api/v1/avatars", auth.token(), Map.of(
                "name", "MathBot",
                "subject", Subject.MATHS.name(),
                "characterType", CharacterType.ZAP.name()));
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        String avatarId = (String) ((Map<String, Object>) created.getBody().get("data"))
                .get("id");

        // Baseline: weekMinutes is all zeros before any duration is logged.
        ResponseEntity<Map> before = get("/api/v1/progress", auth.token());
        List<Integer> weekBefore = (List<Integer>) ((Map<String, Object>)
                before.getBody().get("data")).get("weekMinutes");
        assertThat(weekBefore).isNotNull();
        assertThat(weekBefore.stream().mapToInt(Integer::intValue).sum()).isZero();

        // Report 600 seconds (10 minutes) of reading.
        ResponseEntity<Map> ping = post("/api/v1/progress/reading", auth.token(),
                Map.of("avatarId", avatarId, "durationSeconds", 600));
        assertThat(ping.getStatusCode().is2xxSuccessful()).isTrue();

        // The weekly minutes chart must now show the logged time (10 minutes
        // lands in today's bucket; total across the week is nonzero).
        ResponseEntity<Map> after = get("/api/v1/progress", auth.token());
        List<Integer> weekAfter = (List<Integer>) ((Map<String, Object>)
                after.getBody().get("data")).get("weekMinutes");
        assertThat(weekAfter).isNotNull();
        assertThat(weekAfter.stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(10);
    }

    @SuppressWarnings("unchecked")
    @Test
    void readingPing_clampsOverCeilingDuration() {
        String email = "clamp-" + UUID.randomUUID() + "@test.com";
        AuthResult auth = registerUser(email, "password123");

        ResponseEntity<Map> created = post("/api/v1/avatars", auth.token(), Map.of(
                "name", "MathBot",
                "subject", Subject.MATHS.name(),
                "characterType", CharacterType.ZAP.name()));
        String avatarId = (String) ((Map<String, Object>) created.getBody().get("data"))
                .get("id");

        // 99999 seconds requested → clamps to 3600 (60 minutes).
        ResponseEntity<Map> ping = post("/api/v1/progress/reading", auth.token(),
                Map.of("avatarId", avatarId, "durationSeconds", 99999));
        assertThat(ping.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(((Map<String, Object>) ping.getBody().get("data"))
                .get("durationSeconds")).isEqualTo(3600);

        ResponseEntity<Map> after = get("/api/v1/progress", auth.token());
        List<Integer> weekAfter = (List<Integer>) ((Map<String, Object>)
                after.getBody().get("data")).get("weekMinutes");
        assertThat(weekAfter.stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(60);
    }
}
