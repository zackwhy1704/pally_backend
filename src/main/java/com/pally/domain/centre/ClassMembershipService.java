package com.pally.domain.centre;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.group.ClassGroupService;
import com.pally.domain.organization.ClassEnrollmentService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Class membership: members/assign/remove/roster + analytics.
 * Split from the original ClassService (692-line god-service) to stay ≤8 deps.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassMembershipService {

    private static final int HEATMAP_MAX_STUDENTS = 40;
    private static final int HEATMAP_MAX_TOPICS   = 20;

    private final CentreAccessService accessService;
    private final OrgClassJpaRepository classRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final UserJpaRepository userRepo;
    private final AvatarRepository avatarRepository;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final ClassGroupService classGroupService;
    private final ClassEnrollmentService classEnrollmentService;

    // ── Members (all users in the org + their class memberships) ─────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(String userId, String orgId) {
        accessService.ensureStaff(userId, orgId);
        Map<String, String> classNames = new HashMap<>();
        for (OrgClassJpaEntity c : classRepo.findByOrganizationId(orgId)) {
            classNames.put(c.getId(), c.getName());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserJpaEntity u : userRepo.findByCentreId(orgId)) {
            List<Map<String, Object>> classes = new ArrayList<>();
            for (ClassMembershipJpaEntity m : membershipRepo.findByUserId(u.getId())) {
                if (!ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus())) continue;
                if (!classNames.containsKey(m.getClassId())) continue;
                classes.add(Map.of(
                        "classId", m.getClassId(),
                        "className", classNames.get(m.getClassId())));
            }
            Map<String, Object> row = new HashMap<>();
            row.put("userId", u.getId());
            row.put("displayName", u.getDisplayName() == null ? "" : u.getDisplayName());
            row.put("classes", classes);
            row.put("unassigned", classes.isEmpty());
            out.add(row);
        }
        return out;
    }

    // ── Assign ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> assign(
            String userId, String orgId, String classId, Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);
        String studentId = str(body.get("userId"));
        if (studentId == null || studentId.isBlank())
            throw new BusinessException("userId is required", 400);

        UserJpaEntity student = userRepo.findById(studentId)
                .orElseThrow(() -> new BusinessException("Student not found", 404));
        if (!orgId.equals(student.getCentreId()))
            throw new BusinessException("Student is not a member of this centre", 403);

        String avatarId = classEnrollmentService.enroll(cls, studentId);
        log.info("[Class] assigned student={} class={} avatar={}", studentId, classId, avatarId);
        return Map.of("avatarId", avatarId, "classId", classId, "userId", studentId);
    }

    // ── Remove ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> remove(String userId, String orgId, String classId, String studentId) {
        accessService.ensureStaff(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);
        ClassMembershipJpaEntity existing = membershipRepo.findByClassIdAndUserId(classId, studentId)
                .orElseThrow(() -> new BusinessException("Membership not found", 404));

        // Idempotent: already removed — legacy soft-lock rows or duplicate calls.
        if (!ClassMembershipJpaEntity.STATUS_ACTIVE.equals(existing.getStatus())) {
            return Map.of("removed", true);
        }

        // Hard-delete avatar + membership, mirrors student-initiated leaveClass (INV-3).
        classEnrollmentService.leave(cls, studentId);

        // Clear centreId only if the student has no other active class in this centre.
        boolean stillInCentre = membershipRepo.findByUserId(studentId).stream()
                .filter(m -> ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus()))
                .map(m -> classRepo.findById(m.getClassId()).orElse(null))
                .filter(c -> c != null)
                .anyMatch(c -> orgId.equals(c.getOrganizationId()));
        if (!stillInCentre) {
            userRepo.findById(studentId).ifPresent(u -> {
                if (orgId.equals(u.getCentreId())) {
                    u.setCentreId(null);
                    u.setCohortLabel(null);
                    userRepo.save(u);
                }
            });
        }
        log.info("[Class] teacher-removed student={} class={} stillInCentre={}",
                studentId, classId, stillInCentre);
        return Map.of("removed", true);
    }

    // ── Roster ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> roster(String userId, String orgId, String classId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (ClassMembershipJpaEntity m : membershipRepo.findByClassId(classId)) {
            if (!ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus())) continue;
            UserJpaEntity u = userRepo.findById(m.getUserId()).orElse(null);
            if (u == null) continue;
            out.add(Map.of(
                    "userId", u.getId(),
                    "displayName", u.getDisplayName() == null ? "" : u.getDisplayName(),
                    "avatarId", m.getStudentAvatarId() == null ? "" : m.getStudentAvatarId()));
        }
        return out;
    }

    // ── Analytics: roster with grasp ─────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> classRosterAnalytics(
            String userId, String orgId, String classId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        List<Object[]> rows;
        try {
            rows = quizResultRepo.findStudentActivityByClass(classId);
        } catch (Exception e) {
            log.warn("[Class] roster-analytics query failed class={}: {}", classId, e.getMessage());
            rows = List.of();
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : rows) {
            String studentId = (String) row[0];
            String name = row[1] != null ? (String) row[1] : "";
            double grasp = row[2] != null ? round(((Number) row[2]).doubleValue()) : 0.0;
            long attempts = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            String lastActive = toIsoString(row[4]);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("studentId", studentId);
            m.put("displayName", name);
            m.put("grasp", grasp);
            m.put("attempts", (int) attempts);
            m.put("lastActive", lastActive);
            out.add(m);
        }
        return out;
    }

    // ── Analytics: heatmap ────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> classHeatmap(String userId, String orgId, String classId) {
        accessService.ensureStaff(userId, orgId);
        requireClass(orgId, classId);

        List<Object[]> rows;
        try {
            rows = quizResultRepo.findHeatmapDataByClass(classId);
        } catch (Exception e) {
            log.warn("[Class] heatmap query failed class={}: {}", classId, e.getMessage());
            rows = List.of();
        }

        if (rows.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("students", List.of());
            empty.put("topics", List.of());
            empty.put("cells", List.of());
            empty.put("topicAverages", List.of());
            empty.put("weakest", List.of());
            return empty;
        }

        Map<String, String> studentNames = new LinkedHashMap<>();
        Map<String, Long> topicAttempts = new LinkedHashMap<>();
        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String sid = (String) row[0];
            String topic = (String) row[1];
            double grasp = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
            long n = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            studentNames.putIfAbsent(sid, "");
            topicAttempts.merge(topic, n, Long::sum);
            matrix.computeIfAbsent(sid, k -> new HashMap<>()).put(topic, grasp);
        }
        for (UserJpaEntity u : userRepo.findAllById(studentNames.keySet())) {
            studentNames.put(u.getId(), u.getDisplayName() != null ? u.getDisplayName() : "");
        }

        List<String> studentIds = studentNames.keySet().stream()
                .sorted(Comparator.comparing(id -> studentNames.getOrDefault(id, "")))
                .limit(HEATMAP_MAX_STUDENTS)
                .collect(Collectors.toList());
        List<String> topics = topicAttempts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(HEATMAP_MAX_TOPICS)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());

        List<Map<String, Object>> studentDtos = studentIds.stream().map(id -> {
            String name = studentNames.getOrDefault(id, "");
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", id);
            dto.put("displayName", name);
            dto.put("initials", toInitials(name));
            return dto;
        }).collect(Collectors.toList());

        List<List<Double>> cells = new ArrayList<>();
        Map<String, Double> topicSumMap = new LinkedHashMap<>();
        Map<String, Integer> topicCountMap = new LinkedHashMap<>();
        for (String topic : topics) {
            List<Double> row = new ArrayList<>();
            double sum = 0;
            int n = 0;
            for (String sid : studentIds) {
                Double val = matrix.getOrDefault(sid, Map.of()).get(topic);
                row.add(val);
                if (val != null) { sum += val; n++; }
            }
            cells.add(row);
            topicSumMap.put(topic, sum);
            topicCountMap.put(topic, n);
        }
        List<Map<String, Object>> topicAverages = topics.stream().map(t -> {
            int n = topicCountMap.getOrDefault(t, 0);
            double avg = n > 0 ? topicSumMap.getOrDefault(t, 0.0) / n : 0.0;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("topic", t);
            m.put("avg", round(avg));
            return m;
        }).collect(Collectors.toList());
        List<Map<String, Object>> weakest = topicAverages.stream()
                .sorted(Comparator.comparingDouble(m -> (Double) m.get("avg")))
                .limit(5)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("students", studentDtos);
        result.put("topics", topics);
        result.put("cells", cells);
        result.put("topicAverages", topicAverages);
        result.put("weakest", weakest);
        log.info("[Class] heatmap class={} students={} topics={}", classId, studentIds.size(), topics.size());
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OrgClassJpaEntity requireClass(String orgId, String classId) {
        OrgClassJpaEntity cls = classRepo.findById(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(cls.getOrganizationId()))
            throw new BusinessException("Class not in this organization", 403);
        return cls;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static String toInitials(String displayName) {
        if (displayName == null || displayName.isBlank()) return "??";
        String[] parts = displayName.trim().split("\\s+");
        if (parts.length == 1)
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        return ("" + parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    private static double round(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static String toIsoString(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant().toString();
        if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant().toString();
        if (value instanceof java.time.Instant inst) return inst.toString();
        return value.toString();
    }
}
