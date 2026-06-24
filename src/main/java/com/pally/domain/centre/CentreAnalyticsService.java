package com.pally.domain.centre;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.module.LearningModule;
import com.pally.domain.module.LearningModuleRepository;
import com.pally.domain.module.ModuleContentItem;
import com.pally.domain.module.ModuleContentItemRepository;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.shared.exception.BusinessException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * All analytics aggregation for the centre admin dashboard.
 *
 * <p>This service owns every query, loop, and derived metric that was
 * previously scattered across {@code CentreAnalyticsController}. The
 * controller is now a thin routing shell — it only calls one of these
 * methods per request.
 *
 * <p>Depends only on domain port interfaces; no infrastructure imports.
 *
 * <p>NOTE: the "grasp" metric is raw quiz accuracy (% correct) — it is NOT
 * a full mastery model. It is labelled as such throughout to avoid
 * over-claiming to teachers.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CentreAnalyticsService {

    // ── At-risk thresholds (heuristic — not a validated pedagogical model) ──
    private static final int    INACTIVITY_DAYS        = 7;
    private static final double AT_RISK_HIGH_GRASP     = 0.40;
    private static final double AT_RISK_MEDIUM_GRASP   = 0.55;
    private static final int    AT_RISK_MIN_ATTEMPTS   = 5;

    // ── Heatmap caps — keep the matrix legible ────────────────────────────
    private static final int    HEATMAP_MAX_STUDENTS   = 40;
    private static final int    HEATMAP_MAX_TOPICS     = 20;

    private final UserRepository                userRepository;
    private final QuizQuestionResultRepository  quizResultRepository;
    private final LearningModuleRepository      learningModuleRepository;
    private final ModuleContentItemRepository   moduleItemRepository;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Overview ─────────────────────────────────────────────────────────

    /**
     * Returns the rich summary card for the centre overview page.
     *
     * @param orgId        the organisation ID
     * @param cohortFilter null to span the whole centre; non-null to filter
     * @return map matching the API response shape (never null)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview(String orgId, String cohortFilter) {

        // ── query 1: per-student activity rows (drives most aggregates) ──
        List<Object[]> activityRows;
        try {
            activityRows = quizResultRepository.findStudentActivity(orgId, cohortFilter);
        } catch (Exception e) {
            log.warn("[Centre] student-activity query failed org={}: {}", orgId, e.getMessage());
            activityRows = List.of();
        }

        long totalStudents = userRepository.countByCentreId(orgId);
        Instant sevenDaysAgo = Instant.now().minus(INACTIVITY_DAYS, ChronoUnit.DAYS);

        long activeThisWeek = 0;
        double graspSum = 0;
        int graspCount = 0;
        List<Map<String, Object>> atRisk = new ArrayList<>();

        // Cohort → [studentCount, graspSum*1000, graspCount]
        Map<String, long[]> cohortStats = new LinkedHashMap<>();

        for (Object[] row : activityRows) {
            String studentId   = (String)  row[0];
            String displayName = row[1] != null ? (String) row[1] : "";
            String cohortLabel = row[2] != null ? (String) row[2] : "";
            double grasp14d    = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
            long   attempts14d = row[4] != null ? ((Number) row[4]).longValue()   : 0L;
            Instant lastActive = row[5] != null ? toInstantSafe(row[5]) : null;

            if (lastActive != null && lastActive.isAfter(sevenDaysAgo)) {
                activeThisWeek++;
            }

            if (attempts14d > 0) {
                graspSum += grasp14d;
                graspCount++;
            }

            cohortStats.computeIfAbsent(cohortLabel, k -> new long[]{0, 0, 0});
            long[] cs = cohortStats.get(cohortLabel);
            cs[0]++;
            if (attempts14d > 0) { cs[1] += (long)(grasp14d * 1000); cs[2]++; }

            // At-risk heuristic
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
            for (Object[] row : quizResultRepository.findWeeklyGraspTrend(orgId, cohortFilter)) {
                Map<String, Object> point = new HashMap<>();
                point.put("week",  row[0] != null ? row[0].toString().substring(0, 10) : "");
                point.put("value", row[1] != null ? ((Number) row[1]).doubleValue() : 0.0);
                graspTrend.add(point);
            }
        } catch (Exception e) {
            log.warn("[Centre] weekly-trend query failed org={}: {}", orgId, e.getMessage());
        }

        // ── query 3: distinct topics live ───────────────────────────────
        int topicsLive = 0;
        try {
            topicsLive = quizResultRepository.findWeakestTopicsForCentre(orgId, cohortFilter, 1000).size();
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
                    Instant ia = toInstantSafe(a[5]);
                    Instant ib = toInstantSafe(b[5]);
                    return ib.compareTo(ia);
                })
                .limit(8)
                .map(r -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("studentName", r[1] != null ? r[1] : "");
                    m.put("at", toInstantSafe(r[5]).toString());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeThisWeek", activeThisWeek);
        result.put("totalStudents",  totalStudents);
        result.put("avgGrasp",       round(avgGrasp));
        result.put("graspDelta",     0.0);
        result.put("topicsLive",     topicsLive);
        result.put("atRiskCount",    atRiskCount);
        result.put("graspTrend",     graspTrend);
        result.put("classes",        classes);
        result.put("atRisk",         atRisk);
        result.put("recentActivity", recentActivity);

        log.info("[Centre] overview org={} cohort={} students={} atRisk={}",
                orgId, cohortFilter, totalStudents, atRiskCount);
        return result;
    }

    // ── Cohorts ──────────────────────────────────────────────────────────

    /**
     * Lists all cohorts in the org with student count and average grasp
     * (quiz accuracy) over the last 14 days.
     *
     * @param orgId the organisation ID
     * @return list of cohort summary maps
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> cohorts(String orgId) {
        List<Object[]> activityRows;
        try {
            activityRows = quizResultRepository.findStudentActivity(orgId, null);
        } catch (Exception e) {
            log.warn("[Centre] classes query failed org={}: {}", orgId, e.getMessage());
            activityRows = List.of();
        }

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

        return cohortStats.entrySet().stream()
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
    }

    // ── Heatmap ──────────────────────────────────────────────────────────

    /**
     * Returns the topic × student grasp matrix for a cohort.
     *
     * <p>Matrix cells are {@code null} where a student has no data for that
     * topic (vs. 0.0 which would mean they answered but got none right).
     * Caps: 40 students × 20 topics.
     *
     * @param orgId  the organisation ID
     * @param cohort the cohort label (URL-decoded)
     * @return heatmap payload map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> heatmap(String orgId, String cohort) {
        List<Object[]> rows;
        try {
            rows = quizResultRepository.findHeatmapData(orgId, cohort);
        } catch (Exception e) {
            log.warn("[Centre] heatmap query failed org={} cohort={}: {}",
                    orgId, cohort, e.getMessage());
            rows = List.of();
        }

        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("students",      List.of());
            empty.put("topics",        List.of());
            empty.put("cells",         List.of());
            empty.put("topicAverages", List.of());
            empty.put("weakest",       List.of());
            return empty;
        }

        // userId → displayName (populated after batch fetch)
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

            studentNames.putIfAbsent(studentId, "");
            topicAttempts.merge(topic, n, Long::sum);
            matrix.computeIfAbsent(studentId, k -> new HashMap<>()).put(topic, grasp);
        }

        // Batch fetch student display names via domain port
        List<User> studentEntities = userRepository.findAllByIds(studentNames.keySet());
        for (User u : studentEntities) {
            studentNames.put(u.getId(),
                    u.getDisplayName() != null ? u.getDisplayName() : "");
        }

        // Cap students at HEATMAP_MAX_STUDENTS (alphabetically by display name)
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
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id",          id);
            dto.put("displayName", name);
            dto.put("initials",    toInitials(name));
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
                row.add(val);
                if (val != null) { topicSum += val; topicN++; }
            }
            cells.add(row);
            topicGraspSumMap.put(topic, topicSum);
            topicCountMap.put(topic, topicN);
        }

        List<Map<String, Object>> topicAverages = topics.stream().map(t -> {
            int n = topicCountMap.getOrDefault(t, 0);
            double avg = n > 0 ? topicGraspSumMap.getOrDefault(t, 0.0) / n : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", t);
            m.put("avg",   round(avg));
            return m;
        }).collect(Collectors.toList());

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
                orgId, cohort, studentIds.size(), topics.size());
        return result;
    }

    // ── Student progress ──────────────────────────────────────────────────

    /**
     * Returns the full progress detail page for a single student.
     * Verifies the student belongs to this org — throws 404 otherwise
     * (not 403, to avoid leaking user-existence information).
     *
     * @param orgId         the organisation ID
     * @param studentUserId the student's user ID
     * @return student progress payload map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> studentProgress(String orgId, String studentUserId) {
        User student = userRepository.findById(studentUserId)
                .orElseThrow(() -> new BusinessException("Student not found", 404));

        if (!orgId.equals(student.getCentreId())) {
            throw new BusinessException("Student not found", 404);
        }

        // ── graspOverTime ─────────────────────────────────────────────
        List<Map<String, Object>> graspOverTime = new ArrayList<>();
        try {
            for (Object[] row : quizResultRepository.findWeeklyGraspForStudent(studentUserId)) {
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
            for (Object[] row : quizResultRepository.findTopicGraspForStudent(studentUserId)) {
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
        try {
            List<Object[]> activityRow = quizResultRepository.findStudentActivity(orgId, null);
            for (Object[] row : activityRow) {
                if (Objects.equals(row[0], studentUserId)) {
                    long    questions = row[4] != null ? ((Number) row[4]).longValue() : 0L;
                    Instant lastAct   = row[5] != null ? toInstantSafe(row[5]) : null;
                    String  lastActiveStr = lastAct != null ? lastAct.toString().substring(0, 10) : null;
                    Map<String, Object> eng = new LinkedHashMap<>();
                    eng.put("questions",  (int) questions);
                    eng.put("quizDays",   0);
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
        result.put("engagement",   engagement.isEmpty()
                ? Map.of("questions", 0, "quizDays", 0, "lastActive", null)
                : engagement.get(0));
        result.put("examInDays",   null);

        log.info("[Centre] student-progress org={} student={}", orgId, studentUserId);
        return result;
    }

    // ── Class modules ────────────────────────────────────────────────────

    /**
     * Per-module completion stats across students in a class.
     *
     * @param classId the class ID
     * @return list of module summary maps
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> classModules(String classId) {
        List<LearningModule> modules = learningModuleRepository.findByClassId(classId);

        List<Map<String, Object>> result = new ArrayList<>();
        for (LearningModule module : modules) {
            int totalItems = moduleItemRepository.countByModuleIdAndStage(module.getId(), "PROVE");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("moduleId",       module.getId());
            m.put("title",          module.getTitle());
            m.put("wikiSlug",       module.getWikiPageSlug());
            m.put("stage",          module.getStage());
            m.put("masteryPct",     module.getMasteryPct());
            m.put("proveItemCount", totalItems);
            result.add(m);
        }
        return result;
    }

    // ── Concept mastery ──────────────────────────────────────────────────

    /**
     * Per-concept mastery heatmap from PROVE data across students in a class.
     *
     * @param classId the class ID
     * @return concept mastery payload map
     */
    @Transactional(readOnly = true)
    public Map<String, Object> conceptMastery(String classId) {
        List<LearningModule> modules = learningModuleRepository.findByClassId(classId);

        // Concept → [sumScore, count]
        Map<String, double[]> conceptStats = new LinkedHashMap<>();

        for (LearningModule module : modules) {
            List<ModuleContentItem> proveItems =
                    moduleItemRepository.findByModuleIdAndStageOrderBySortOrder(module.getId(), "PROVE");
            for (ModuleContentItem item : proveItems) {
                String concept = null;
                try {
                    var node = MAPPER.readTree(item.getAnswerJson());
                    concept = node.path("targetConcept").asText(null);
                } catch (Exception ignored) {}
                if (concept == null || concept.isBlank()) continue;
                conceptStats.computeIfAbsent(concept, k -> new double[]{0, 0});
            }
        }

        List<Map<String, Object>> concepts = conceptStats.entrySet().stream()
                .map(e -> {
                    double[] s = e.getValue();
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("concept",  e.getKey());
                    m.put("avgScore", s[1] > 0 ? round(s[0] / s[1]) : 0.0);
                    m.put("attempts", (int) s[1]);
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("classId",  classId);
        result.put("concepts", concepts);
        // Belt-and-braces: the web reads students/cells/weakest too. The per-student
        // concept grid isn't computed at class level yet, but the keys must always
        // be present as arrays (never absent/null) so the client never crashes on
        // `.map`. It degrades to the quiz-heatmap fallback when students is empty.
        result.put("students", List.of());
        result.put("cells",    List.of());
        result.put("weakest",  List.of());
        return result;
    }

    // ── Cost summary ──────────────────────────────────────────────────────

    /**
     * Returns an in-process cost summary by scanning the provided
     * {@link MeterRegistry} for {@code pally.user.ai_cost} counters.
     *
     * <p>The registry is accepted as a parameter (not injected) so that the
     * domain service does not take a compile-time dependency on Micrometer's
     * Spring-managed bean. The controller passes it in from its own injection.
     *
     * <p>NOTE: counters reset on deploy; this is best-effort for the admin
     * dashboard's "since last deploy" view.
     *
     * @param orgId    the organisation ID (used only to derive per-student avg)
     * @param registry the in-process Micrometer registry
     * @return cost summary payload map
     */
    public Map<String, Object> costSummary(String orgId, MeterRegistry registry) {
        double totalCostCents = 0;
        Map<String, Double> perTask  = new LinkedHashMap<>();
        Map<String, Double> perModel = new LinkedHashMap<>();

        for (var meter : registry.getMeters()) {
            if (!"pally.user.ai_cost".equals(meter.getId().getName())) continue;
            if (!(meter instanceof Counter counter)) continue;

            double amount = counter.count();
            if (amount <= 0) continue;

            totalCostCents += amount;

            String task  = meter.getId().getTag("task");
            String model = meter.getId().getTag("model");
            if (task  != null) perTask.merge(task,   amount, Double::sum);
            if (model != null) perModel.merge(model, amount, Double::sum);
        }

        long   studentCount   = userRepository.countByCentreId(orgId);
        double perStudentAvg  = studentCount > 0 ? totalCostCents / studentCount : 0;

        List<Map<String, Object>> breakdown = new ArrayList<>();
        for (var taskEntry : perTask.entrySet()) {
            for (var modelEntry : perModel.entrySet()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("task",      taskEntry.getKey());
                item.put("model",     modelEntry.getKey());
                item.put("costCents", round(taskEntry.getValue()));
                breakdown.add(item);
            }
        }
        breakdown.sort(Comparator.<Map<String, Object>, Double>comparing(
                m -> (Double) m.get("costCents")).reversed());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalCostCents", round(totalCostCents));
        result.put("perStudentAvg",  round(perStudentAvg));
        result.put("breakdown",      breakdown);

        log.info("[Centre] cost-summary org={} total={}", orgId, round(totalCostCents));
        return result;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** Converts "Alice Tan" → "AT". Falls back to "??" for blank/null. */
    public static String toInitials(String displayName) {
        if (displayName == null || displayName.isBlank()) return "??";
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length == 1) return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    public static Instant toInstantSafe(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (value instanceof Instant inst) return inst;
        return Instant.parse(value.toString());
    }

    /** Trims and nullifies blank cohort param; null signals "whole centre" to native queries. */
    public static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** Rounds grasp (quiz accuracy) to 4 decimal places to avoid JSON noise. */
    public static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
