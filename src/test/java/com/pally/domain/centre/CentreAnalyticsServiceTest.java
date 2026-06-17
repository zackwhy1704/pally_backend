package com.pally.domain.centre;

import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import com.pally.domain.module.ModuleContentItem;
import com.pally.domain.module.ModuleContentItemRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito unit tests for {@link CentreAnalyticsService}.
 *
 * <p>No Spring context — just domain port mocks.
 * Every test name describes the invariant being asserted.
 */
@ExtendWith(MockitoExtension.class)
class CentreAnalyticsServiceTest {

    @Mock UserRepository               userRepository;
    @Mock QuizQuestionResultRepository quizResultRepository;
    @Mock LearningModuleRepository     learningModuleRepository;
    @Mock ModuleContentItemRepository  moduleItemRepository;

    @InjectMocks CentreAnalyticsService service;

    private static final String ORG_ID    = "org-1";
    private static final String STUDENT_A = "student-a";
    private static final String STUDENT_B = "student-b";

    // ── Overview ──────────────────────────────────────────────────────────

    @Test
    void overview_returnsAllExpectedTopLevelKeys() {
        stubActivityRows(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.75, 10)));
        stubWeeklyTrend(rows(trendRow("2026-05-05", 0.68)));
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(1L);

        Map<String, Object> result = service.overview(ORG_ID, null);

        assertThat(result).containsKeys(
                "activeThisWeek", "totalStudents", "avgGrasp", "graspDelta",
                "topicsLive", "atRiskCount", "graspTrend", "classes", "atRisk", "recentActivity");
    }

    @Test
    void overview_highGraspActiveStudent_isNotInAtRiskList() {
        // grasp=0.80 > AT_RISK_MEDIUM_GRASP(0.55), lastActive within 7 days
        stubActivityRows(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.80, 10)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk =
                (List<Map<String, Object>>) service.overview(ORG_ID, null).get("atRisk");

        assertThat(atRisk).isEmpty();
    }

    @Test
    void overview_lowGraspStudent_flaggedHighSeverityWithLowGraspReason() {
        // grasp=0.30 < AT_RISK_HIGH_GRASP(0.40), attempts=8 >= 5
        stubActivityRows(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.30, 8)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk =
                (List<Map<String, Object>>) service.overview(ORG_ID, null).get("atRisk");

        assertThat(atRisk).hasSize(1);
        assertThat(atRisk.get(0).get("severity")).isEqualTo("high");
        assertThat(atRisk.get(0).get("reason")).isEqualTo("Low grasp");
    }

    @Test
    void overview_developingGraspStudent_flaggedMediumSeverity() {
        // grasp=0.50, between HIGH(0.40) and MEDIUM(0.55), attempts >= 5
        stubActivityRows(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.50, 6)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk =
                (List<Map<String, Object>>) service.overview(ORG_ID, null).get("atRisk");

        assertThat(atRisk).hasSize(1);
        assertThat(atRisk.get(0).get("severity")).isEqualTo("medium");
        assertThat(atRisk.get(0).get("reason")).isEqualTo("Developing");
    }

    @Test
    void overview_inactiveStudent_flaggedHighSeverityWithInactiveReason() {
        // lastActive = 10 days ago > INACTIVITY_DAYS(7)
        stubActivityRows(rows(inactiveStudentRow(STUDENT_A, "Sec3A", 10)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(1L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> atRisk =
                (List<Map<String, Object>>) service.overview(ORG_ID, null).get("atRisk");

        assertThat(atRisk).hasSize(1);
        assertThat(atRisk.get(0).get("severity")).isEqualTo("high");
        assertThat((String) atRisk.get(0).get("reason")).startsWith("Inactive");
    }

    @Test
    void overview_emptyCentre_returnsZeroStudentsAndEmptyAtRisk() {
        stubActivityRows(Collections.emptyList());
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(0L);

        Map<String, Object> result = service.overview(ORG_ID, null);

        assertThat(result.get("totalStudents")).isEqualTo(0L);
        assertThat((List<?>) result.get("atRisk")).isEmpty();
    }

    @Test
    void overview_twoStudentsInDifferentCohorts_bothAppearsInClasses() {
        stubActivityRows(rows(
                activeStudentRow(STUDENT_A, "Sec3A", 0.80, 10),
                activeStudentRow(STUDENT_B, "Sec3B", 0.70, 5)));
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(2L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes =
                (List<Map<String, Object>>) service.overview(ORG_ID, null).get("classes");

        assertThat(classes).hasSize(2);
        assertThat(classes.stream().map(m -> m.get("cohort")))
                .containsExactlyInAnyOrder("Sec3A", "Sec3B");
    }

    @Test
    void overview_recentActivityTopEightSortedByLastActiveDesc() {
        // 10 distinct activity rows — only the 8 most-recent should appear
        List<Object[]> tenRows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tenRows.add(new Object[]{
                    "student-" + i,
                    "Student " + i,
                    "Sec3A",
                    0.70,
                    5L,
                    Timestamp.from(Instant.now().minus(i, ChronoUnit.HOURS))
            });
        }
        stubActivityRows(tenRows);
        stubWeeklyTrend(Collections.emptyList());
        stubWeakestTopics(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(10L);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentActivity =
                (List<Map<String, Object>>) service.overview(ORG_ID, null).get("recentActivity");

        assertThat(recentActivity).hasSize(8);
        // First entry should be student-0 (most recently active: 0 hours ago)
        assertThat(recentActivity.get(0).get("studentName")).isEqualTo("Student 0");
    }

    // ── Cohorts ───────────────────────────────────────────────────────────

    @Test
    void cohorts_groupsByCorrectCohortLabel() {
        stubActivityRowsForCohorts(rows(
                activeStudentRow(STUDENT_A, "Sec3A", 0.70, 5),
                activeStudentRow(STUDENT_B, "Sec3B", 0.80, 5)));

        List<Map<String, Object>> result = service.cohorts(ORG_ID);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(m -> m.get("cohort")))
                .containsExactlyInAnyOrder("Sec3A", "Sec3B");
    }

    @Test
    void cohorts_emptyCentre_returnsEmptyList() {
        stubActivityRowsForCohorts(Collections.emptyList());

        assertThat(service.cohorts(ORG_ID)).isEmpty();
    }

    @Test
    void cohorts_avgGraspCorrectlyAveragedAcrossStudentsInSameCohort() {
        // Two students in Sec3A: grasp 0.60 and 0.80 → avg = 0.70
        stubActivityRowsForCohorts(rows(
                activeStudentRow(STUDENT_A, "Sec3A", 0.60, 5),
                activeStudentRow(STUDENT_B, "Sec3A", 0.80, 5)));

        List<Map<String, Object>> result = service.cohorts(ORG_ID);

        assertThat(result).hasSize(1);
        double avg = (Double) result.get(0).get("avgGrasp");
        assertThat(avg).isBetween(0.69, 0.71);
    }

    // ── Heatmap ───────────────────────────────────────────────────────────

    @Test
    void heatmap_emptyRows_returnsValidEmptyShapes() {
        when(quizResultRepository.findHeatmapData(ORG_ID, "Sec3A"))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = service.heatmap(ORG_ID, "Sec3A");

        assertThat((List<?>) result.get("students")).isEmpty();
        assertThat((List<?>) result.get("topics")).isEmpty();
        assertThat((List<?>) result.get("cells")).isEmpty();
        assertThat((List<?>) result.get("topicAverages")).isEmpty();
        assertThat((List<?>) result.get("weakest")).isEmpty();
    }

    @Test
    void heatmap_nullCellWhenStudentHasNoDataForTopic() {
        // STUDENT_A has data for "integration" only; STUDENT_B has both topics
        List<Object[]> heatmapData = rows(
                heatmapRow(STUDENT_A, "integration",     0.80, 4),
                heatmapRow(STUDENT_B, "integration",     0.60, 4),
                heatmapRow(STUDENT_B, "differentiation", 0.40, 3));

        when(quizResultRepository.findHeatmapData(ORG_ID, "Sec3A")).thenReturn(heatmapData);
        when(userRepository.findAllByIds(any())).thenReturn(List.of(
                makeUser(STUDENT_A, "Alice", ORG_ID),
                makeUser(STUDENT_B, "Bob",   ORG_ID)));

        Map<String, Object> result = service.heatmap(ORG_ID, "Sec3A");

        @SuppressWarnings("unchecked")
        List<String> topics = (List<String>) result.get("topics");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> students = (List<Map<String, Object>>) result.get("students");
        @SuppressWarnings("unchecked")
        List<List<Double>> cells = (List<List<Double>>) result.get("cells");

        int diffIdx = topics.indexOf("differentiation");
        assertThat(diffIdx).isGreaterThanOrEqualTo(0);

        int aliceIdx = -1;
        for (int i = 0; i < students.size(); i++) {
            if (STUDENT_A.equals(students.get(i).get("id"))) { aliceIdx = i; break; }
        }
        assertThat(aliceIdx).isGreaterThanOrEqualTo(0);
        // STUDENT_A has no data for "differentiation" → must be null, not 0.0
        assertThat(cells.get(diffIdx).get(aliceIdx)).isNull();
    }

    @Test
    void heatmap_topicAveragesCorrectlyComputed() {
        // Two students both have data for "integration": 0.80 and 0.60 → avg = 0.70
        List<Object[]> heatmapData = rows(
                heatmapRow(STUDENT_A, "integration", 0.80, 4),
                heatmapRow(STUDENT_B, "integration", 0.60, 4));

        when(quizResultRepository.findHeatmapData(ORG_ID, "Sec3A")).thenReturn(heatmapData);
        when(userRepository.findAllByIds(any())).thenReturn(List.of(
                makeUser(STUDENT_A, "Alice", ORG_ID),
                makeUser(STUDENT_B, "Bob",   ORG_ID)));

        Map<String, Object> result = service.heatmap(ORG_ID, "Sec3A");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> topicAverages = (List<Map<String, Object>>) result.get("topicAverages");
        assertThat(topicAverages).hasSize(1);
        double avg = (Double) topicAverages.get(0).get("avg");
        assertThat(avg).isBetween(0.69, 0.71);
    }

    // ── Student progress ──────────────────────────────────────────────────

    @Test
    void studentProgress_returnsAllExpectedFieldsForKnownStudent() {
        User student = makeUser(STUDENT_A, "Alice Tan", ORG_ID);
        student.setStreakDays(5);
        student.setLevel(4);
        student.setXp(320);
        student.setCohortLabel("Sec3A");

        when(userRepository.findById(STUDENT_A)).thenReturn(Optional.of(student));
        when(quizResultRepository.findWeeklyGraspForStudent(STUDENT_A))
                .thenReturn(rows(trendRow("2026-05-05", 0.65)));
        when(quizResultRepository.findTopicGraspForStudent(STUDENT_A))
                .thenReturn(rows(topicGraspRow("integration", 0.80, 15)));
        lenient().when(quizResultRepository.findStudentActivity(eq(ORG_ID), isNull()))
                .thenReturn(rows(activeStudentRow(STUDENT_A, "Sec3A", 0.75, 20)));

        Map<String, Object> result = service.studentProgress(ORG_ID, STUDENT_A);

        assertThat(result.get("studentId")).isEqualTo(STUDENT_A);
        assertThat(result.get("displayName")).isEqualTo("Alice Tan");
        assertThat(result.get("streakDays")).isEqualTo(5);
        assertThat(result.get("level")).isEqualTo(4);
        assertThat(result).containsKeys("grasp", "graspOverTime", "topicGrasp", "engagement", "examInDays");
    }

    @Test
    void studentProgress_unknownStudent_throws404() {
        when(userRepository.findById("no-such")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.studentProgress(ORG_ID, "no-such"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void studentProgress_studentBelongingToDifferentOrg_throws404ToAvoidLeakingExistence() {
        User student = makeUser(STUDENT_A, "Alice", "different-org");
        when(userRepository.findById(STUDENT_A)).thenReturn(Optional.of(student));

        assertThatThrownBy(() -> service.studentProgress(ORG_ID, STUDENT_A))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void studentProgress_graspQueriesRouteOnlyThroughCentreAvatarScopedPort() {
        // INV-2 (PRIVACY): the weekly-grasp and topic-grasp queries must only reach
        // centre-avatar results. At the service level this is enforced by routing all
        // reads through QuizQuestionResultRepository (the domain port), whose JPA
        // implementation has JOIN avatars a ON a.centre_avatar = TRUE. This test
        // verifies the service never bypasses the port to call an unconstrained method.
        User student = makeUser(STUDENT_A, "Alice Tan", ORG_ID);
        when(userRepository.findById(STUDENT_A)).thenReturn(Optional.of(student));
        // Port returns centre-only rows (SOLO results are filtered at the SQL level).
        when(quizResultRepository.findWeeklyGraspForStudent(STUDENT_A))
                .thenReturn(rows(trendRow("2026-06-01", 0.72)));
        when(quizResultRepository.findTopicGraspForStudent(STUDENT_A))
                .thenReturn(rows(topicGraspRow("algebra", 0.65, 8)));
        lenient().when(quizResultRepository.findStudentActivity(eq(ORG_ID), isNull()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = service.studentProgress(ORG_ID, STUDENT_A);

        // Service must call the constrained port methods exactly once each.
        verify(quizResultRepository).findWeeklyGraspForStudent(STUDENT_A);
        verify(quizResultRepository).findTopicGraspForStudent(STUDENT_A);
        // Results surfaced to the teacher come only from what the port returned.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> graspOverTime =
                (List<Map<String, Object>>) result.get("graspOverTime");
        assertThat(graspOverTime).hasSize(1);
        assertThat(graspOverTime.get(0).get("value")).isEqualTo(0.72);
    }

    @Test
    void studentProgress_noQuizHistory_returnsZeroEngagementGracefully() {
        User student = makeUser(STUDENT_A, "Alice", ORG_ID);
        when(userRepository.findById(STUDENT_A)).thenReturn(Optional.of(student));
        when(quizResultRepository.findWeeklyGraspForStudent(STUDENT_A)).thenReturn(Collections.emptyList());
        when(quizResultRepository.findTopicGraspForStudent(STUDENT_A)).thenReturn(Collections.emptyList());
        when(quizResultRepository.findStudentActivity(eq(ORG_ID), isNull()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> result = service.studentProgress(ORG_ID, STUDENT_A);

        @SuppressWarnings("unchecked")
        Map<String, Object> engagement = (Map<String, Object>) result.get("engagement");
        assertThat(engagement.get("questions")).isEqualTo(0);
        assertThat(engagement.get("lastActive")).isNull();
    }

    // ── Cost summary ──────────────────────────────────────────────────────

    @Test
    void costSummary_noMeters_returnsAllZerosAndEmptyBreakdown() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.getMeters()).thenReturn(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(5L);

        Map<String, Object> result = service.costSummary(ORG_ID, registry);

        assertThat((Double) result.get("totalCostCents")).isEqualTo(0.0);
        assertThat((Double) result.get("perStudentAvg")).isEqualTo(0.0);
        assertThat((List<?>) result.get("breakdown")).isEmpty();
    }

    @Test
    void costSummary_withCounterForOneTaskAndModel_computesPerStudentAverage() {
        MeterRegistry registry = mock(MeterRegistry.class);

        Meter.Id id = new Meter.Id(
                "pally.user.ai_cost",
                Tags.of("task", "chat", "model", "sonnet"),
                null, null, Meter.Type.COUNTER);
        Counter counter = mock(Counter.class);
        when(counter.getId()).thenReturn(id);
        when(counter.count()).thenReturn(10.0);

        when(registry.getMeters()).thenReturn(List.of(counter));
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(2L);

        Map<String, Object> result = service.costSummary(ORG_ID, registry);

        assertThat((Double) result.get("totalCostCents")).isEqualTo(10.0);
        assertThat((Double) result.get("perStudentAvg")).isEqualTo(5.0);
        assertThat((List<?>) result.get("breakdown")).hasSize(1);
    }

    @Test
    void costSummary_noStudents_perStudentAvgIsZeroRatherThanDivideByZero() {
        MeterRegistry registry = mock(MeterRegistry.class);
        when(registry.getMeters()).thenReturn(Collections.emptyList());
        when(userRepository.countByCentreId(ORG_ID)).thenReturn(0L);

        Map<String, Object> result = service.costSummary(ORG_ID, registry);

        assertThat((Double) result.get("perStudentAvg")).isEqualTo(0.0);
    }

    // ── Helper tests ──────────────────────────────────────────────────────

    @Test
    void toInitials_twoWordName_returnsTwoLetterInitials() {
        assertThat(CentreAnalyticsService.toInitials("Alice Tan")).isEqualTo("AT");
    }

    @Test
    void toInitials_singleWordName_returnsTwoCharsUppercased() {
        assertThat(CentreAnalyticsService.toInitials("alice")).isEqualTo("AL");
    }

    @Test
    void toInitials_nullOrBlankName_returnsFallback() {
        assertThat(CentreAnalyticsService.toInitials(null)).isEqualTo("??");
        assertThat(CentreAnalyticsService.toInitials("   ")).isEqualTo("??");
    }

    @Test
    void blankToNull_blankString_returnsNull() {
        assertThat(CentreAnalyticsService.blankToNull("  ")).isNull();
        assertThat(CentreAnalyticsService.blankToNull(null)).isNull();
    }

    @Test
    void blankToNull_nonBlankString_returnsTrimmedValue() {
        assertThat(CentreAnalyticsService.blankToNull("  Sec3A  ")).isEqualTo("Sec3A");
    }

    @Test
    void round_valueTrimmedToFourDecimalPlaces() {
        assertThat(CentreAnalyticsService.round(0.123456789)).isEqualTo(0.1235);
    }

    // ── Row builders ──────────────────────────────────────────────────────

    /** Active student row: [id, displayName, cohortLabel, grasp14d, attempts14d, lastActive] */
    private static Object[] activeStudentRow(String id, String cohort, double grasp, long attempts) {
        return new Object[]{
                id, "Student " + id, cohort, grasp, attempts,
                Timestamp.from(Instant.now().minus(2, ChronoUnit.DAYS))
        };
    }

    /** Inactive student with lastActive {@code daysAgo} days ago. */
    private static Object[] inactiveStudentRow(String id, String cohort, int daysAgo) {
        return new Object[]{
                id, "Student " + id, cohort, 0.5, 10L,
                Timestamp.from(Instant.now().minus(daysAgo, ChronoUnit.DAYS))
        };
    }

    /** Heatmap row: [userId, topicSlug, grasp, n] */
    private static Object[] heatmapRow(String userId, String topic, double grasp, long n) {
        return new Object[]{userId, topic, grasp, n};
    }

    /** Trend row: [week (Timestamp), grasp] */
    private static Object[] trendRow(String week, double grasp) {
        return new Object[]{Timestamp.valueOf(week + " 00:00:00"), grasp};
    }

    /** Topic grasp row: [topicSlug, grasp, n] */
    private static Object[] topicGraspRow(String topic, double grasp, long n) {
        return new Object[]{topic, grasp, n};
    }

    private static List<Object[]> rows(Object[]... arrays) {
        List<Object[]> list = new ArrayList<>();
        for (Object[] arr : arrays) list.add(arr);
        return list;
    }

    private User makeUser(String id, String name, String centreId) {
        User u = new User();
        u.setId(id);
        u.setDisplayName(name);
        u.setCentreId(centreId);
        return u;
    }

    // ── Stub helpers ──────────────────────────────────────────────────────

    /** Stubs both any() and isNull() forms of findStudentActivity — mirrors overview() call pattern. */
    private void stubActivityRows(List<Object[]> rows) {
        lenient().when(quizResultRepository.findStudentActivity(eq(ORG_ID), any())).thenReturn(rows);
        lenient().when(quizResultRepository.findStudentActivity(eq(ORG_ID), isNull())).thenReturn(rows);
    }

    /** Stubs findStudentActivity with null cohort filter (used by cohorts()). */
    private void stubActivityRowsForCohorts(List<Object[]> rows) {
        lenient().when(quizResultRepository.findStudentActivity(eq(ORG_ID), isNull())).thenReturn(rows);
        lenient().when(quizResultRepository.findStudentActivity(eq(ORG_ID), any())).thenReturn(rows);
    }

    private void stubWeeklyTrend(List<Object[]> rows) {
        lenient().when(quizResultRepository.findWeeklyGraspTrend(eq(ORG_ID), any())).thenReturn(rows);
        lenient().when(quizResultRepository.findWeeklyGraspTrend(eq(ORG_ID), isNull())).thenReturn(rows);
    }

    private void stubWeakestTopics(List<Object[]> rows) {
        lenient().when(quizResultRepository.findWeakestTopicsForCentre(eq(ORG_ID), any(), anyInt())).thenReturn(rows);
        lenient().when(quizResultRepository.findWeakestTopicsForCentre(eq(ORG_ID), isNull(), anyInt())).thenReturn(rows);
    }
}
