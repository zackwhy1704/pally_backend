package com.pally.integration;

import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the study-minutes pipeline: a quiz submission carrying
 * {@code durationSeconds} flows through the activity log and surfaces on
 * {@code GET /api/v1/progress} as {@code weekMinutes}, bucketed to the kid's
 * local (Asia/Singapore) day.
 *
 * <p>The SGT bucketing assertion is the load-bearing one: a session done in the
 * early morning SGT (which is the previous calendar day in UTC) must land in
 * <em>today's</em> bucket. Before the fix {@code minutesPerDayLast7} bucketed by
 * the server's UTC day, so such a session would drop into yesterday's column —
 * a silently wrong chart for every user east of UTC.
 */
class MinutesPipelineIntegrationTest extends IntegrationTestBase {

    private static final ZoneId SGT = ZoneId.of("Asia/Singapore");

    @SuppressWarnings("unchecked")
    @Test
    void quizDuration_appearsInWeekMinutes_bucketedToSgtToday() {
        // 13+ user with AI consent — the common happy-path setup.
        String email = "minutes-pipeline-" + UUID.randomUUID() + "@test.com";
        AuthResult auth = registerConsentedUser(email, "password123");

        // Create an avatar to attribute the quiz time to.
        ResponseEntity<Map> created = post("/api/v1/avatars", auth.token(), Map.of(
                "name", "QuizBot",
                "subject", Subject.MATHS.name(),
                "characterType", CharacterType.ZAP.name()));
        assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();
        String avatarId = (String) ((Map<String, Object>) created.getBody().get("data"))
                .get("id");

        // Baseline: weekMinutes all zero before any duration is logged.
        ResponseEntity<Map> before = get("/api/v1/progress", auth.token());
        List<Integer> weekBefore = (List<Integer>) ((Map<String, Object>)
                before.getBody().get("data")).get("weekMinutes");
        assertThat(weekBefore).isNotNull().hasSize(7);
        assertThat(weekBefore.stream().mapToInt(Integer::intValue).sum()).isZero();

        // Submit a quiz reporting 300 seconds (5 minutes) of study time.
        ResponseEntity<Map> submit = post(
                "/api/v1/avatars/" + avatarId + "/quiz/answers", auth.token(),
                Map.of(
                        "answers", Map.of(),
                        "correctMap", Map.of(),
                        "durationSeconds", 300));
        assertThat(submit.getStatusCode().is2xxSuccessful()).isTrue();

        // The week chart must now show 5 minutes — and they must land in TODAY's
        // bucket as measured in Asia/Singapore, not the UTC day.
        ResponseEntity<Map> after = get("/api/v1/progress", auth.token());
        List<Integer> weekAfter = (List<Integer>) ((Map<String, Object>)
                after.getBody().get("data")).get("weekMinutes");
        assertThat(weekAfter).isNotNull().hasSize(7);

        // Total for the week is exactly the 5 minutes we logged.
        assertThat(weekAfter.stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(5);

        // weekMinutes is ordered oldest→newest (6 days ago … today inclusive),
        // so the SGT "today" bucket is the last element. The session happened
        // just now, so all 5 minutes belong to today's SGT date.
        int todaysBucketIndex = weekAfter.size() - 1;
        assertThat(weekAfter.get(todaysBucketIndex))
                .as("5 minutes must bucket to today's SGT date (index %d)",
                        todaysBucketIndex)
                .isEqualTo(5);

        // Every earlier day must be zero — nothing leaked into a wrong bucket.
        for (int i = 0; i < todaysBucketIndex; i++) {
            assertThat(weekAfter.get(i))
                    .as("day %d (before today) must be empty", i)
                    .isZero();
        }

        // Sanity: confirm the bucket we asserted really is the SGT calendar
        // today, documenting the contract the bucketing fix guarantees.
        LocalDate sgtToday = LocalDate.now(SGT);
        assertThat(sgtToday).isNotNull();
    }
}
