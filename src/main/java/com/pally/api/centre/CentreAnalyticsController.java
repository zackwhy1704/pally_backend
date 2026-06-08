package com.pally.api.centre;

import com.pally.domain.centre.CentreAccessService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Centre analytics endpoints for the admin web dashboard.
 *
 * <p>Authorization: all routes require the caller to be the organization's
 * {@code owner_user_id} — enforced via {@link CentreAccessService#ensureOwner}.
 *
 * <p>All data is read-only. No new tables are created.
 *
 * <p>NOTE: the "grasp" metric is raw quiz accuracy (% correct) — it is NOT
 * a full mastery model. It is labelled as such throughout to avoid
 * over-claiming to teachers.
 */
@RestController
@RequestMapping("/api/v1/centre")
@RequiredArgsConstructor
@Slf4j
public class CentreAnalyticsController {

    // ── At-risk thresholds (heuristic — not a validated pedagogical model) ──
    private static final int    INACTIVITY_DAYS        = 7;
    private static final double AT_RISK_HIGH_GRASP     = 0.40;
    private static final double AT_RISK_MEDIUM_GRASP   = 0.55;
    private static final int    AT_RISK_MIN_ATTEMPTS   = 5;

    // ── Heatmap caps — keep the matrix legible ────────────────────────────
    private static final int    HEATMAP_MAX_STUDENTS   = 40;
    private static final int    HEATMAP_MAX_TOPICS     = 20;

    private final CentreAccessService accessService;
    private final UserJpaRepository userRepo;
    private final QuizQuestionResultJpaRepository quizResultRepo;

    // ── GET /organizations/{orgId}/overview?cohort= ────────────────────────

    /**
     * Returns a rich summary card for the centre overview page.
     *
     * <p>Runs 3 DB queries (student activity, weekly trend, weakest topics);
     * all other aggregates are derived in memory to keep trip count minimal.
     */
    @GetMapping("/organizations/{orgId}/overview")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> overview(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestParam(required = false) String cohort) {

        accessService.ensureOwner(userId, orgId);

        String cohortFilter = blankToNull(cohort);

        // ── query 1: per-student activity rows (drives most aggregates) ──
        List<Object[]> activityRows;
        try {
            activityRows = quizResultRepo.findStudentActivity(orgId, cohortFilter);
        } catch (Exception e) {
            log.warn("[Centre] student-activity query failed org={}: {}", orgId, e.getMessage());
            activityRows = List.of();
        }

        long totalStudents = userRepo.countByCentreId(orgId);
        Instant sevenDaysAgo = Instant.now().minus(INACTIVITY_DAYS, ChronoUnit.DAYS);

        long activeThisWeek = 0;
        double graspSum = 0;
        int graspCount = 0;
        List<Map<String, Object>> atRisk = new ArrayList<>();

        // Cohort → [studentCount, graspSum, graspCount]
        Map<String, long[]> cohortStats = new LinkedHashMap<>();

        for (Object[] row : activityRows) {
            String studentId        = (String)  row[0];
            String displayName      = row[1] != null ? (String) row[1] : "";
            String cohortLabel      = row[2] != null ? (String) row[2] : "";
            double grasp14d         = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
            long   attempts14d      = row[4] != null ? ((Number) row[4]).longValue()   : 0L;
            Instant lastActive      = row[5] != null
                    ? ((java.sql.Timestamp) row[5]).toInstant() : null;

            // Active-this-week count
            if (lastActive != null && lastActive.isAfter(sevenDaysAgo)) {
                activeThisWeek++;
            }

            // Overall grasp average (only students with attempts contribute)
            if (attempts14d > 0) {
                graspSum += grasp14d;
                graspCount++;
            }

            // Per-cohort rollup
            cohortStats.computeIfAbsent(cohortLabel, k -> new long[]{0, 0, 0});
            long[] cs = cohortStats.get(cohortLabel);
            cs[0]++;                                           // studentCount
            if (attempts14d > 0) { cs[1] += (long)(grasp14d * 1000); cs[2]++; }

            // At-risk heuristic (inline comment: threshold values are configurable at top of class)
            String reason   = null;
            String severity = null;

            if (lastActive == null || lastActive.isBefore(sevenDaysAgo)) {
                long inactiveDays = lastActive == null ? Long.MAX_VALUE
                        : ChronoUnit.DAYS.between(lastActive, Instant.now());
                reason   = "Inactive " + (inactiveDays == Long.MAX_VALUE ? "always" : inactiveDays + "d");
                severity = "high";
            } else if (attempts14d >= AT_RISK_MIN_ATTEMPTS && grasp14d < AT_RISK_HIGH_GRASP) {
                reason   = "Low grasp";
                severity = "high";
            } else if (attempts14d >= AT_RISK_MIN_ATTEMPTS && grasp14d < AT_RISK_MEDIUM_GRASP) {
                reason   = "Developing";
                severity = "medium";
            }

            if (reason != null) {
                Map<String, Object> item = new HashMap<>();
                item.put("studentId", studentId);
                item.put("name",      displayName);
                item.put("cohort",    cohortLabel);
                item.put("reason",    reason);
                item.put("severity",  severity);
                atRisk.add(item);
            }
        }

        double avgGrasp    = graspCount > 0 ? graspSum / graspCount : 0.0;
        long   atRiskCount = atRisk.size();

        // ── query 2: weekly grasp trend ─────────────────────────────────
        List<Map<String, Object>> graspTrend = new ArrayList<>();
        try {
            for (Object[] row : quizResultRepo.findWeeklyGraspTrend(orgId, cohortFilter)) {
                Map<String, Object> point = new HashMap<>();
                point.put("week",  row[0] != null ? row[0].toString().substring(0, 10) : "");
                point.put("value", row[1] != null ? ((Number) row[1]).doubleValue() : 0.0);
                graspTrend.add(point);
            }
        } catch (Exception e) {
            log.warn("[Centre] weekly-trend query failed org={}: {}", orgId, e.getMessage());
        }

        // ── query 3: distinct topics live (reuse existing weakest query) ─
        int topicsLive = 0;
        try {
            topicsLive = quizResultRepo.findWeakestTopicsForCentre(orgId, cohortFilter, 1000).size();
        } catch (Exception e) {
            log.warn("[Centre] topics-live query failed org={}: {}", orgId, e.getMessage());
        }

        // ── class rollup derived from activity rows ────────────────────
        List<Map<String, Object>> classes = cohortStats.entrySet().stream()
                .map(e -> {
                    long[] cs = e.getValue();
                    double avg = cs[2] > 0 ? (cs[1] / 1000.0) / cs[2] : 0.0;
                    Map<String, Object> m = new HashMap<>();
                    m.put("cohort",       e.getKey());
                    m.put("studentCount", (int) cs[0]);
                    m.put("avgGrasp",     avg);
                    return m;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("cohort")))
                .collect(Collectors.toList());

        // ── recent activity: top 8 from the activity rows by last_active ─
        List<Map<String, Object>> recentActivity = activityRows.stream()
                .filter(r -> r[5] != null)
                .sorted((a, b) -> {
                    Instant ia = ((java.sql.Timestamp) a[5]).toInstant();
                    Instant ib = ((java.sql.Timestamp) b[5]).toInstant();
                    return ib.compareTo(ia);
                })
                .limit(8)
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("studentName", r[1] != null ? r[1] : "");
                    m.put("at", ((java.sql.Timestamp) r[5]).toInstant().toString());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeThisWeek", activeThisWeek);
        result.put("totalStudents",  totalStudents);
        result.put("avgGrasp",       round(avgGrasp));
        result.put("graspDelta",     0.0); // MVP: delta is 0 when only one window available
        result.put("topicsLive",     topicsLive);
        result.put("atRiskCount",    atRiskCount);
        result.put("graspTrend",     graspTrend);
        result.put("classes",        classes);
        result.put("atRisk",         atRisk);
        result.put("recentActivity", recentActivity);

        log.info("[Centre] overview org={} cohort={} students={} atRisk={}",
                orgId, cohortFilter, totalStudents, atRiskCount);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── GET /organizations/{orgId}/cohorts ────────────────────────────────
    // NOTE: renamed from /classes — the canonical class list is now owned by
    // ClassController (the first-class org_class CRUD). This legacy endpoint
    // still summarises by users.cohort_label for backward compatibility.

    /**
     * Lists all cohorts in the org with their student count and average
     * grasp (quiz accuracy) over the last 14 days.
     */
    @GetMapping("/organizations/{orgId}/cohorts")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> classes(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {

        accessService.ensureOwner(userId, orgId);

        List<Object[]> activityRows;
        try {
            activityRows = quizResultRepo.findStudentActivity(orgId, null);
        } catch (Exception e) {
            log.warn("[Centre] classes query failed org={}: {}", orgId, e.getMessage());
            activityRows = List.of();
        }

        // Group by cohort_label
        Map<String, long[]> cohortStats = new LinkedHashMap<>();
        for (Object[] row : activityRows) {
            String cohortLabel = row[2] != null ? (String) row[2] : "";
            long   attempts14d = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            double grasp14d    = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;

            cohortStats.computeIfAbsent(cohortLabel, k -> new long[]{0, 0, 0});
            long[] cs = cohortStats.get(cohortLabel);
            cs[0]++;
            if (attempts14d > 0) { cs[1] += (long)(grasp14d * 1000); cs[2]++; }
        }

        List<Map<String, Object>> result = cohortStats.entrySet().stream()
                .map(e -> {
                    long[] cs = e.getValue();
                    double avg = cs[2] > 0 ? (cs[1] / 1000.0) / cs[2] : 0.0;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("cohort",       e.getKey());
                    m.put("studentCount", (int) cs[0]);
                    m.put("avgGrasp",     round(avg));
                    return m;
                })
                .sorted(Comparator.comparing(m -> (String) m.get("cohort")))
                .collect(Collectors.toList());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── GET /organizations/{orgId}/classes/{cohort}/heatmap ────────────────

    /**
     * Returns a topic × student grasp (quiz accuracy) matrix for a cohort.
     *
     * <p>Matrix cells are {@code null} where a student has no data for that
     * topic (as opposed to 0.0, which would mean they answered but got none
     * right). The caller should render null as a grey "no data" cell.
     *
     * <p>Caps: 40 students, 20 topics (lowest-attempt topics dropped first).
     */
    @GetMapping("/organizations/{orgId}/classes/{cohort}/heatmap")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> heatmap(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String cohort) {

        accessService.ensureOwner(userId, orgId);

        String decodedCohort = URLDecoder.decode(cohort, StandardCharsets.UTF_8);

        List<Object[]> rows;
        try {
            rows = quizResultRepo.findHeatmapData(orgId, decodedCohort);
        } catch (Exception e) {
            log.warn("[Centre] heatmap query failed org={} cohort={}: {}",
                    orgId, decodedCohort, e.getMessage());
            rows = List.of();
        }

        if (rows.isEmpty()) {
            // Return empty-but-valid shapes so the frontend doesn't need null guards
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("students",      List.of());
            empty.put("topics",        List.of());
            empty.put("cells",         List.of());
            empty.put("topicAverages", List.of());
            empty.put("weakest",       List.of());
            return ResponseEntity.ok(ApiResponse.success(empty));
        }

        // ── collect unique students + student display names ────────────
        // userId → [displayName, totalAttempts across all topics]
        Map<String, String> studentNames = new LinkedHashMap<>();
        // topic → totalAttempts (to cap topics by dropping lowest-attempt ones)
        Map<String, Long> topicAttempts = new LinkedHashMap<>();
        // (userId, topic) → grasp
        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();

        for (Object[] row : rows) {
            String studentId = (String)  row[0];
            String topic     = (String)  row[1];
            double grasp     = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
            long   n         = row[3] != null ? ((Number) row[3]).longValue()   : 0L;

            studentNames.putIfAbsent(studentId, "");  // name unknown at this stage
            topicAttempts.merge(topic, n, Long::sum);
            matrix.computeIfAbsent(studentId, k -> new HashMap<>()).put(topic, grasp);
        }

        // Fetch student display names in one batch
        List<UserJpaEntity> studentEntities = userRepo.findAllById(studentNames.keySet());
        for (UserJpaEntity u : studentEntities) {
            studentNames.put(u.getId(),
                    u.getDisplayName() != null ? u.getDisplayName() : "");
        }

        // Cap students at HEATMAP_MAX_STUDENTS (just take the first N alphabetically)
        List<String> studentIds = studentNames.keySet().stream()
                .sorted(Comparator.comparing(id -> studentNames.getOrDefault(id, "")))
                .limit(HEATMAP_MAX_STUDENTS)
                .collect(Collectors.toList());

        // Cap topics at HEATMAP_MAX_TOPICS — drop lowest-attempt topics first
        List<String> topics = topicAttempts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(HEATMAP_MAX_TOPICS)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());

        // Build student DTOs
        List<Map<String, Object>> studentDtos = studentIds.stream().map(id -> {
            String name    = studentNames.getOrDefault(id, "");
            String initials = toInitials(name);
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id",          id);
            dto.put("displayName", name);
            dto.put("initials",    initials);
            return dto;
        }).collect(Collectors.toList());

        // Build cells matrix: rows = topics, cols = students
        List<List<Double>> cells = new ArrayList<>();
        Map<String, Double> topicGraspSumMap = new LinkedHashMap<>();
        Map<String, Integer> topicCountMap   = new LinkedHashMap<>();

        for (String topic : topics) {
            List<Double> row = new ArrayList<>();
            double topicSum  = 0;
            int    topicN    = 0;
            for (String sid : studentIds) {
                Double val = matrix.getOrDefault(sid, Map.of()).get(topic);
                row.add(val);  // null = no data for this student/topic
                if (val != null) { topicSum += val; topicN++; }
            }
            cells.add(row);
            topicGraspSumMap.put(topic, topicSum);
            topicCountMap.put(topic, topicN);
        }

        // Topic averages
        List<Map<String, Object>> topicAverages = topics.stream().map(t -> {
            int n = topicCountMap.getOrDefault(t, 0);
            double avg = n > 0 ? topicGraspSumMap.getOrDefault(t, 0.0) / n : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", t);
            m.put("avg",   round(avg));
            return m;
        }).collect(Collectors.toList());

        // Weakest 5 topics by average grasp (quiz accuracy)
        List<Map<String, Object>> weakest = topicAverages.stream()
                .sorted(Comparator.comparingDouble(m -> (Double) m.get("avg")))
                .limit(5)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("students",      studentDtos);
        result.put("topics",        topics);
        result.put("cells",         cells);
        result.put("topicAverages", topicAverages);
        result.put("weakest",       weakest);

        log.info("[Centre] heatmap org={} cohort={} students={} topics={}",
                orgId, decodedCohort, studentIds.size(), topics.size());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── GET /organizations/{orgId}/students/{studentUserId} ───────────────

    /**
     * Returns the full progress detail page for a single student.
     * Verifies the student belongs to this org — returns 404 otherwise
     * (not 403, to avoid leaking user-existence information).
     */
    @GetMapping("/organizations/{orgId}/students/{studentUserId}")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> studentProgress(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String studentUserId) {

        accessService.ensureOwner(userId, orgId);

        UserJpaEntity student = userRepo.findById(studentUserId)
                .orElseThrow(() -> new BusinessException("Student not found", 404));

        // Tenant isolation: ensure student belongs to this org
        if (!orgId.equals(student.getCentreId())) {
            throw new BusinessException("Student not found", 404);
        }

        // ── graspOverTime ─────────────────────────────────────────────
        List<Map<String, Object>> graspOverTime = new ArrayList<>();
        try {
            for (Object[] row : quizResultRepo.findWeeklyGraspForStudent(studentUserId)) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("week",  row[0] != null ? row[0].toString().substring(0, 10) : "");
                point.put("value", row[1] != null ? round(((Number) row[1]).doubleValue()) : 0.0);
                graspOverTime.add(point);
            }
        } catch (Exception e) {
            log.warn("[Centre] weekly-grasp-for-student failed student={}: {}",
                    studentUserId, e.getMessage());
        }

        // ── topicGrasp ────────────────────────────────────────────────
        List<Map<String, Object>> topicGrasp = new ArrayList<>();
        double overallGraspSum = 0;
        int    overallGraspN   = 0;
        try {
            for (Object[] row : quizResultRepo.findTopicGraspForStudent(studentUserId)) {
                String topic  = (String) row[0];
                double grasp  = row[1] != null ? ((Number) row[1]).doubleValue() : 0.0;
                long   n      = row[2] != null ? ((Number) row[2]).longValue()   : 0L;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("topic",    topic);
                m.put("grasp",    round(grasp));
                m.put("attempts", (int) n);
                topicGrasp.add(m);
                overallGraspSum += grasp;
                overallGraspN++;
            }
        } catch (Exception e) {
            log.warn("[Centre] topic-grasp-for-student failed student={}: {}",
                    studentUserId, e.getMessage());
        }

        double overallGrasp = overallGraspN > 0 ? overallGraspSum / overallGraspN : 0.0;

        // ── engagement (derived from activity query for this student) ──
        List<Map<String, Object>> engagement = new ArrayList<>();
        String lastActiveStr = null;
        try {
            List<Object[]> activityRow = quizResultRepo.findStudentActivity(orgId, null);
            for (Object[] row : activityRow) {
                if (Objects.equals(row[0], studentUserId)) {
                    long   questions = row[4] != null ? ((Number) row[4]).longValue() : 0L;
                    Instant lastAct  = row[5] != null
                            ? ((java.sql.Timestamp) row[5]).toInstant() : null;
                    lastActiveStr = lastAct != null ? lastAct.toString().substring(0, 10) : null;
                    Map<String, Object> eng = new LinkedHashMap<>();
                    eng.put("questions",  (int) questions);
                    eng.put("quizDays",   0);  // accurate quizDays requires a separate COUNT(DISTINCT) query; MVP uses 0
                    eng.put("lastActive", lastActiveStr);
                    engagement.add(eng);
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("[Centre] engagement query failed student={}: {}", studentUserId, e.getMessage());
        }
        if (engagement.isEmpty()) {
            Map<String, Object> eng = new LinkedHashMap<>();
            eng.put("questions",  0);
            eng.put("quizDays",   0);
            eng.put("lastActive", null);
            engagement.add(eng);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("studentId",    studentUserId);
        result.put("displayName",  student.getDisplayName() != null ? student.getDisplayName() : "");
        result.put("cohortLabel",  student.getCohortLabel() != null ? student.getCohortLabel() : "");
        result.put("streakDays",   student.getStreakDays());
        result.put("level",        student.getLevel());
        result.put("xp",           student.getXp());
        result.put("grasp",        round(overallGrasp));
        result.put("graspOverTime", graspOverTime);
        result.put("topicGrasp",   topicGrasp);
        result.put("engagement",   engagement.isEmpty() ? Map.of("questions", 0, "quizDays", 0, "lastActive", null) : engagement.get(0));
        result.put("examInDays",   null);  // MVP: no test-date field on user entity yet

        log.info("[Centre] student-progress org={} student={}", orgId, studentUserId);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Converts "Alice Tan" → "AT". Falls back to "??" for blank/null. */
    private static String toInitials(String displayName) {
        if (displayName == null || displayName.isBlank()) return "??";
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    /** Trims and nullifies blank cohort param; null signals "whole centre" to native queries. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Rounds grasp (quiz accuracy) to 4 decimal places to avoid JSON noise. */
    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
