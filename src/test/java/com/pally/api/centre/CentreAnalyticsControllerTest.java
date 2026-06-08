package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CentreAnalyticsControllerTest {

    @Mock CentreAccessService accessService;
    @Mock UserJpaRepository userRepo;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;

    @InjectMocks CentreAnalyticsController controller;

    private static final String OWNER_ID  = "owner-1";
    private static final String ORG_ID    = "org-1";
    private static final String STUDENT_A = "student-a";
    private static final String STUDENT_B = "student-b";

    private OrganizationJpaEntity org;

    @BeforeEach
    void setUp() {
        org = new OrganizationJpaEntity();
        org.setId(ORG_ID);
        org.setName("Test Centre");
        org.setOwnerUserId(OWNER_ID);

        // Default: ensureOwner succeeds and returns the org entity.
        // lenient() so that tests that call a different userId don't trigger UnnecessaryStubbingException.
        lenient().when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org);
    }

    // ── Overview tests ────────────────────────────────────────────────────

    @Test
    void overview_returnsAllTopLevelFields() {
        List<Object[]> activity = rows(activeStudentRow(STUDENT_A, "Sec3A", 0.75, 10));
        List<Object[]> trend    = rows(trendRow("2026-05-05", 0.68));
        stubActivityRows(activity);
        stubWeeklyTrend(trend);
        stubWeakestTopics(Collections.emptyList());
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(1L);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.overview(OWNER_ID, ORG_ID, null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody().data();
        assertThat(body).containsKeys(
                "activeThisWeek", "totalStudents", "avgGrasp", "graspDelta",
                "topicsLive", "atRiskCount", "graspTrend", "classes", "atRisk", "recentActivity");
        assertThat(body.get("totalStudents")).isEqualTo(1L);
    }

    @Test
    void overview_highGraspStudent_isNotAtRisk() {
        // grasp = 0.80 > AT_RISK_MEDIUM_GRASP (0.55), attempts = 10 ≥ 5
        stubActivityRows(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.80, 10)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(1L);

        var body = controller.overview(OWNER_ID, ORG_ID, null).getBody().data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk = (List<Map<String, Object>>) body.get("atRisk");
        assertThat(atRisk).isEmpty();
    }

    @Test
    void overview_lowGraspStudent_flaggedHighSeverity() {
        // grasp = 0.30 < AT_RISK_HIGH_GRASP (0.40), attempts = 8 ≥ 5
        stubActivityRows(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.30, 8)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(1L);

        var body = controller.overview(OWNER_ID, ORG_ID, null).getBody().data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk = (List<Map<String, Object>>) body.get("atRisk");
        assertThat(atRisk).hasSize(1);
        assertThat(atRisk.get(0).get("severity")).isEqualTo("high");
        assertThat(atRisk.get(0).get("reason")).isEqualTo("Low grasp");
    }

    @Test
    void overview_mediumGraspStudent_flaggedMediumSeverity() {
        // grasp = 0.50 >= AT_RISK_HIGH_GRASP (0.40) but < AT_RISK_MEDIUM_GRASP (0.55)
        stubActivityRows(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.50, 6)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(1L);

        var body = controller.overview(OWNER_ID, ORG_ID, null).getBody().data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk = (List<Map<String, Object>>) body.get("atRisk");
        assertThat(atRisk).hasSize(1);
        assertThat(atRisk.get(0).get("severity")).isEqualTo("medium");
    }

    @Test
    void overview_inactiveStudent_flaggedHighSeverityWithReason() {
        // last_active = 10 days ago > INACTIVITY_DAYS (7)
        stubActivityRows(rows(inactiveStudentRow(STUDENT_A, "Sec3A", 10)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(1L);

        var body = controller.overview(OWNER_ID, ORG_ID, null).getBody().data();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk = (List<Map<String, Object>>) body.get("atRisk");
        assertThat(atRisk).hasSize(1);
        assertThat(atRisk.get(0).get("severity")).isEqualTo("high");
        assertThat((String) atRisk.get(0).get("reason")).startsWith("Inactive");
    }

    @Test
    void overview_nonOwner_throws403() {
        when(accessService.ensureOwner("other-user", ORG_ID))
                .thenThrow(new BusinessException("You don't own this organization", 403));

        assertThatThrownBy(() -> controller.overview("other-user", ORG_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("don't own");
    }

    // ── Classes tests ─────────────────────────────────────────────────────

    @Test
    void classes_groupsByCorrectCohort() {
        stubActivityRows(rows(
                activeStudentRow(STUDENT_A, "Sec3A", 0.70, 5),
                activeStudentRow(STUDENT_B, "Sec3B", 0.80, 5)
        ));

        var response = controller.classes(OWNER_ID, ORG_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        List<Map<String, Object>> body = response.getBody().data();
        assertThat(body).hasSize(2);
        assertThat(body.stream().map(m -> m.get("cohort")))
                .containsExactlyInAnyOrder("Sec3A", "Sec3B");
    }

    @Test
    void classes_emptyCentre_returnsEmptyList() {
        stubActivityRows(Collections.emptyList());

        var response = controller.classes(OWNER_ID, ORG_ID);
        assertThat(response.getBody().data()).isEmpty();
    }

    // ── Heatmap tests ─────────────────────────────────────────────────────

    @Test
    void heatmap_correctMatrixDimensions() {
        List<Object[]> heatmapData = rows(
                heatmapRow(STUDENT_A, "integration",     0.80, 4),
                heatmapRow(STUDENT_A, "differentiation", 0.60, 3),
                heatmapRow(STUDENT_B, "integration",     0.50, 4)
        );
        when(quizResultRepo.findHeatmapData(eq(ORG_ID), anyString())).thenReturn(heatmapData);

        UserJpaEntity ua = makeUser(STUDENT_A, "Alice Tan", "Sec3A");
        UserJpaEntity ub = makeUser(STUDENT_B, "Bob Lee",   "Sec3A");
        when(userRepo.findAllById(any())).thenReturn(java.util.Arrays.asList(ua, ub));

        var response = controller.heatmap(OWNER_ID, ORG_ID, "Sec3A");
        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody().data();
        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) body.get("topics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) body.get("students");
        @SuppressWarnings("unchecked")
        List<List<Double>> cells = (List<List<Double>>) body.get("cells");

        assertThat(topics).hasSize(2);
        assertThat(students).hasSize(2);
        // rows = topics count, each row has cols = students count
        assertThat(cells).hasSize(2);
        assertThat(cells.get(0)).hasSize(2);
    }

    @Test
    void heatmap_nullCellWhenStudentHasNoDataForTopic() {
        // STUDENT_A has data for "integration" but NOT for "differentiation"
        List<Object[]> heatmapData = rows(
                heatmapRow(STUDENT_A, "integration",     0.80, 4),
                heatmapRow(STUDENT_B, "integration",     0.60, 4),
                heatmapRow(STUDENT_B, "differentiation", 0.40, 3)
        );
        when(quizResultRepo.findHeatmapData(eq(ORG_ID), anyString())).thenReturn(heatmapData);

        UserJpaEntity ua = makeUser(STUDENT_A, "Alice", "Sec3A");
        UserJpaEntity ub = makeUser(STUDENT_B, "Bob",   "Sec3A");
        when(userRepo.findAllById(any())).thenReturn(java.util.Arrays.asList(ua, ub));

        var response = controller.heatmap(OWNER_ID, ORG_ID, "Sec3A");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody().data();
        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) body.get("topics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) body.get("students");
        @SuppressWarnings("unchecked")
        List<List<Double>> cells = (List<List<Double>>) body.get("cells");

        // Find the row for "differentiation"
        int diffIdx = topics.indexOf("differentiation");
        assertThat(diffIdx).isGreaterThanOrEqualTo(0);
        // Find the column for STUDENT_A
        int aliceIdx = -1;
        for (int i = 0; i < students.size(); i++) {
            if (STUDENT_A.equals(students.get(i).get("id"))) { aliceIdx = i; break; }
        }
        assertThat(aliceIdx).isGreaterThanOrEqualTo(0);
        // STUDENT_A has no data for differentiation → cell must be null
        assertThat(cells.get(diffIdx).get(aliceIdx)).isNull();
    }

    @Test
    void heatmap_emptyOrg_returnsEmptyValidShapes() {
        when(quizResultRepo.findHeatmapData(eq(ORG_ID), anyString()))
                .thenReturn(Collections.emptyList());

        var response = controller.heatmap(OWNER_ID, ORG_ID, "Sec3A");
        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> body = response.getBody().data();
        assertThat((List<?>) body.get("students")).isEmpty();
        assertThat((List<?>) body.get("topics")).isEmpty();
        assertThat((List<?>) body.get("cells")).isEmpty();
        assertThat((List<?>) body.get("topicAverages")).isEmpty();
        assertThat((List<?>) body.get("weakest")).isEmpty();
    }

    // ── Student progress tests ────────────────────────────────────────────

    @Test
    void studentProgress_studentInOrg_returns200WithAllFields() {
        UserJpaEntity student = makeUser(STUDENT_A, "Alice Tan", "Sec3A");
        student.setCentreId(ORG_ID);
        student.setStreakDays(5);
        student.setLevel(4);
        student.setXp(320);

        when(userRepo.findById(STUDENT_A)).thenReturn(Optional.of(student));
        when(quizResultRepo.findWeeklyGraspForStudent(STUDENT_A))
                .thenReturn(rows(trendRow("2026-05-05", 0.65)));
        when(quizResultRepo.findTopicGraspForStudent(STUDENT_A))
                .thenReturn(rows(topicGraspRow("integration", 0.80, 15)));
        when(quizResultRepo.findStudentActivity(eq(ORG_ID), isNull()))
                .thenReturn(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.75, 20)));

        var response = controller.studentProgress(OWNER_ID, ORG_ID, STUDENT_A);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> body = response.getBody().data();
        assertThat(body.get("studentId")).isEqualTo(STUDENT_A);
        assertThat(body.get("displayName")).isEqualTo("Alice Tan");
        assertThat(body.get("streakDays")).isEqualTo(5);
        assertThat(body.get("level")).isEqualTo(4);
        assertThat(body).containsKeys("grasp", "graspOverTime", "topicGrasp", "engagement", "examInDays");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topicGrasp = (List<Map<String, Object>>) body.get("topicGrasp");
        assertThat(topicGrasp).hasSize(1);
        assertThat(topicGrasp.get(0).get("topic")).isEqualTo("integration");
    }

    @Test
    void studentProgress_studentNotInOrg_returns404() {
        UserJpaEntity student = makeUser(STUDENT_A, "Alice Tan", "Sec3A");
        student.setCentreId("different-org");

        when(userRepo.findById(STUDENT_A)).thenReturn(Optional.of(student));

        assertThatThrownBy(() -> controller.studentProgress(OWNER_ID, ORG_ID, STUDENT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void studentProgress_unknownStudentId_returns404() {
        when(userRepo.findById("no-such-student")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.studentProgress(OWNER_ID, ORG_ID, "no-such-student"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    // ── Tenant isolation ──────────────────────────────────────────────────

    @Test
    void overview_nonOwnerOfOrg_throws403ViaAccessService() {
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't own this organization", 403));

        assertThatThrownBy(() -> controller.overview("intruder", ORG_ID, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("don't own");
    }

    @Test
    void heatmap_nonOwner_throws403ViaAccessService() {
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("You don't own this organization", 403));

        assertThatThrownBy(() -> controller.heatmap("intruder", ORG_ID, "Sec3A"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("don't own");
    }

    // ── centre_avatar=false exclusion ─────────────────────────────────────

    @Test
    void overview_emptyCentre_returns200WithEmptyAtRisk() {
        // An org with no students (or all students inactive) should still return 200
        stubActivityRows(Collections.emptyList());
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepo.countByCentreId(ORG_ID)).thenReturn(0L);

        var response = controller.overview(OWNER_ID, ORG_ID, null);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody().data();
        assertThat(body.get("totalStudents")).isEqualTo(0L);
        @SuppressWarnings("unchecked")
        List<?> atRisk = (List<?>) body.get("atRisk");
        assertThat(atRisk).isEmpty();
    }

    @Test
    void classes_emptyOrg_returns200EmptyList() {
        // Empty centre — no activity rows at all
        stubActivityRows(Collections.emptyList());

        var response = controller.classes(OWNER_ID, ORG_ID);

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(response.getBody().data()).isEmpty();
    }

    @Test
    void heatmap_nonCentreAvatarStudent_appearsInHeatmapStructureButQueriesExcludeThem() {
        // The heatmap query itself is responsible for filtering out non-centre-avatar results
        // (JOIN avatars a ON a.id = r.avatar_id AND a.centre_avatar = TRUE).
        // At the controller level: if the repo returns empty (because only personal avatars
        // had results), the heatmap should return 200 with empty shapes.
        when(quizResultRepo.findHeatmapData(eq(ORG_ID), anyString()))
                .thenReturn(Collections.emptyList());

        var response = controller.heatmap(OWNER_ID, ORG_ID, "Sec3A");

        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> body = response.getBody().data();
        assertThat((List<?>) body.get("students")).isEmpty();
        assertThat((List<?>) body.get("topics")).isEmpty();
        assertThat((List<?>) body.get("cells")).isEmpty();
    }

    // ── Row builder helpers ────────────────────────────────────────────────

    /**
     * Active student (last_active within 7 days).
     * Columns: [id, display_name, cohort_label, grasp_14d, attempts_14d, last_active]
     */
    private static Object[] activeStudentRow(String id, String cohort, double grasp, long attempts) {
        return new Object[]{
                id,
                "Student " + id,
                cohort,
                grasp,
                attempts,
                Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS))
        };
    }

    /**
     * Inactive student with last_active {@code daysAgo} days in the past.
     */
    private static Object[] inactiveStudentRow(String id, String cohort, int daysAgo) {
        return new Object[]{
                id,
                "Student " + id,
                cohort,
                0.5,
                10L,
                Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS))
        };
    }

    /** Heatmap row: [user_id, topic_slug, grasp, n] */
    private static Object[] heatmapRow(String userId, String topic, double grasp, long n) {
        return new Object[]{userId, topic, grasp, n};
    }

    /** Trend row: [week (Timestamp), grasp] */
    private static Object[] trendRow(String week, double grasp) {
        return new Object[]{Timestamp.valueOf(week + " 00:00:00"), grasp};
    }

    /** Topic grasp row: [topic_slug, grasp, n] */
    private static Object[] topicGraspRow(String topic, double grasp, long n) {
        return new Object[]{topic, grasp, n};
    }

    /**
     * Type-safe builder for {@code List<Object[]>} that avoids the
     * {@code List.of(Object[])} type-inference ambiguity (Java sees {@code List<Object>}).
     */
    private static List<Object[]> rows(Object[]... arrays) {
        List<Object[]> list = new ArrayList<>();
        for (Object[] arr : arrays) list.add(arr);
        return list;
    }

    private UserJpaEntity makeUser(String id, String name, String cohort) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        u.setDisplayName(name);
        u.setCohortLabel(cohort);
        u.setCentreId(ORG_ID);
        return u;
    }

    // ── Stub helpers ────────────────────────────────────────────────────
    // Use lenient() so that unused stubs (e.g. isNull() when any() was consumed) don't fail.

    private void stubActivityRows(List<Object[]> actRows) {
        lenient().when(quizResultRepo.findStudentActivity(eq(ORG_ID), any())).thenReturn(actRows);
        lenient().when(quizResultRepo.findStudentActivity(eq(ORG_ID), isNull())).thenReturn(actRows);
    }

    private void stubWeeklyTrend(List<Object[]> trendRows) {
        lenient().when(quizResultRepo.findWeeklyGraspTrend(eq(ORG_ID), any())).thenReturn(trendRows);
        lenient().when(quizResultRepo.findWeeklyGraspTrend(eq(ORG_ID), isNull())).thenReturn(trendRows);
    }

    private void stubWeakestTopics(List<Object[]> topicRows) {
        lenient().when(quizResultRepo.findWeakestTopicsForCentre(eq(ORG_ID), any(), anyInt())).thenReturn(topicRows);
        lenient().when(quizResultRepo.findWeakestTopicsForCentre(eq(ORG_ID), isNull(), anyInt())).thenReturn(topicRows);
    }
}
