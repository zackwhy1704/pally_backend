package com.pally.api.centre;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.api.centre.dto.MochiConfig;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.group.ClassGroupService;
import com.pally.domain.organization.ClassEnrollmentService;
import com.pally.domain.module.NarrationService;

import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.response.ApiResponse;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Centre classes (subject×level). A class owns a join code, a shared corpus,
 * a branded Mochi, and its own roster. Admins create classes and assign centre
 * members into them; each assignment provisions the student's branded, closed-book
 * centre avatar. All endpoints are owner-gated via {@link CentreAccessService}.
 */
@RestController
@RequestMapping("/api/v1/centre/organizations/{orgId}")
@RequiredArgsConstructor
@Slf4j
public class ClassController {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LEN = 8;

    private final CentreAccessService accessService;
    private final OrgClassJpaRepository classRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final UserJpaRepository userRepo;
    private final AvatarRepository avatarRepository;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final NarrationService narrationService;
    private final ClassGroupService classGroupService;
    private final ClassEnrollmentService classEnrollmentService;
    private final ObjectMapper objectMapper;

    // Heatmap legibility caps (mirror CentreAnalyticsController).
    private static final int HEATMAP_MAX_STUDENTS = 40;
    private static final int HEATMAP_MAX_TOPICS = 20;

    // ── Create a class ────────────────────────────────────────────────────────

    @PostMapping("/classes")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> createClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);

        String name = str(body.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException("name is required", 400);

        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setId(IdGenerator.newId());
        cls.setOrganizationId(orgId);
        cls.setName(name);
        cls.setSubject(str(body.get("subject")));
        cls.setLevel(str(body.get("level")));
        cls.setJoinCode(generateCode());
        cls.setCharacterType(parseCharacter(str(body.get("characterType"))).name());
        cls.setBrandName(str(body.get("brandName")));
        cls.setAccentColor(str(body.get("accentColor")));
        cls.setExamDate(parseDate(str(body.get("examDate"))));
        cls.setCosmeticEyewear(str(body.get("cosmeticEyewear")));
        cls.setCosmeticClothes(str(body.get("cosmeticClothes")));
        cls.setCosmeticShoes(str(body.get("cosmeticShoes")));

        // Provision a hidden corpus avatar (owner-owned) that holds the class's
        // shared wiki. class_id stays null on it so analytics never count it as a
        // student; content uploaded "to the class" targets this avatar id.
        Avatar corpus = Avatar.create(
                userId, name + " Corpus",
                parseSubject(cls.getSubject()),
                parseCharacter(cls.getCharacterType()),
                cls.getLevel(), null);
        // Tag as a CENTRE_CLASS avatar (kind + centre flag) so the economy never
        // sees it and the client renders the class uniform. class_id stays null
        // on the corpus so analytics (which group by avatars.class_id) never
        // count it as a student; the class points back to it via corpusAvatarId.
        corpus.markCentreClassAvatar();
        Avatar savedCorpus = avatarRepository.save(corpus);
        cls.setCorpusAvatarId(savedCorpus.getId());
        // Default a deterministic Mochi look so every class renders a distinct
        // customised avatar immediately (never the plain fallback) even if the
        // teacher never opens the picker. Body variant derived from the class id.
        try {
            int bodyVariant = Math.floorMod(cls.getId().hashCode(),
                    MochiConfig.BODY_VARIANT_MAX + 1);
            cls.setMochiConfig(objectMapper.writeValueAsString(
                    new MochiConfig(bodyVariant, "none", "none")));
        } catch (JsonProcessingException e) {
            log.warn("[Class] could not default mochi-config for {}: {}",
                    cls.getId(), e.getMessage());
        }
        classRepo.save(cls);
        // Auto-create the class's CLASS group (membership syncs from enrolment;
        // the centre owner sits in it as TEACHER).
        classGroupService.ensureClassGroup(cls);
        log.info("[Class] created class={} org={} code={}", cls.getId(), orgId, cls.getJoinCode());
        return ResponseEntity.ok(ApiResponse.success(toDto(cls, 0)));
    }

    // ── List classes ──────────────────────────────────────────────────────────

    @GetMapping("/classes")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listClasses(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        accessService.ensureOwner(userId, orgId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrgClassJpaEntity cls : classRepo.findByOrganizationId(orgId)) {
            long count = membershipRepo.countByClassIdAndStatus(
                    cls.getId(), ClassMembershipJpaEntity.STATUS_ACTIVE);
            out.add(toDto(cls, count));
        }
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Edit a class (name/brand/character/level/accent/exam/cosmetics) ────────

    @PatchMapping("/classes/{classId}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);

        if (body.containsKey("name")) cls.setName(str(body.get("name")));
        if (body.containsKey("subject")) cls.setSubject(str(body.get("subject")));
        if (body.containsKey("level")) cls.setLevel(str(body.get("level")));
        if (body.containsKey("characterType"))
            cls.setCharacterType(parseCharacter(str(body.get("characterType"))).name());
        if (body.containsKey("brandName")) cls.setBrandName(str(body.get("brandName")));
        if (body.containsKey("accentColor")) cls.setAccentColor(str(body.get("accentColor")));
        if (body.containsKey("examDate")) cls.setExamDate(parseDate(str(body.get("examDate"))));
        if (body.containsKey("cosmeticEyewear")) cls.setCosmeticEyewear(str(body.get("cosmeticEyewear")));
        if (body.containsKey("cosmeticClothes")) cls.setCosmeticClothes(str(body.get("cosmeticClothes")));
        if (body.containsKey("cosmeticShoes")) cls.setCosmeticShoes(str(body.get("cosmeticShoes")));
        classRepo.save(cls);

        // Propagate branding/character/cosmetics to every provisioned student avatar.
        for (ClassMembershipJpaEntity m : membershipRepo.findByClassId(classId)) {
            if (m.getStudentAvatarId() == null) continue;
            avatarRepository.findById(m.getStudentAvatarId()).ifPresent(a -> {
                applyClassConfig(a, cls);
                avatarRepository.save(a);
            });
        }
        long count = membershipRepo.countByClassIdAndStatus(
                classId, ClassMembershipJpaEntity.STATUS_ACTIVE);
        return ResponseEntity.ok(ApiResponse.success(toDto(cls, count)));
    }

    // ── Delete a class ─────────────────────────────────────────────────────────

    /**
     * Owner-gated. Permanently deletes a class and everything provisioned with it:
     * each enrolled student's class avatar, the hidden corpus avatar (their wiki /
     * files / chat cascade on avatar delete), and the CLASS study group. The class
     * row delete cascades class_membership + assignment via their FK constraints.
     */
    @DeleteMapping("/classes/{classId}")
    @Transactional
    public ResponseEntity<Void> deleteClass(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);

        // Remove every provisioned student avatar (content cascades on delete).
        for (ClassMembershipJpaEntity m : membershipRepo.findByClassId(classId)) {
            if (m.getStudentAvatarId() != null) {
                avatarRepository.deleteById(m.getStudentAvatarId());
            }
        }
        // Remove the hidden corpus avatar (its wiki / files / chat cascade).
        if (cls.getCorpusAvatarId() != null) {
            avatarRepository.deleteById(cls.getCorpusAvatarId());
        }
        // Remove the CLASS group (members + system posts cascade via FK).
        classGroupService.deleteClassGroup(classId);
        // Finally the class row — class_membership + assignment cascade via FK.
        classRepo.delete(cls);
        log.info("[Class] deleted class={} org={}", classId, orgId);
        return ResponseEntity.noContent().build();
    }

    // ── Set the class Mochi customization config ───────────────────────────────

    /**
     * Owner-gated. Validates the rich MochiConfig, serializes it to JSON and stores
     * it in {@code org_class.mochi_config} (TEXT). Returns the saved MochiConfig.
     */
    @PatchMapping("/classes/{classId}/mochi-config")
    @Transactional
    public ResponseEntity<ApiResponse<MochiConfig>> updateMochiConfig(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody MochiConfig config) {
        accessService.ensureOwner(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);

        if (config == null) throw new BusinessException("mochi-config body is required", 400);
        MochiConfig validated = config.validated();

        try {
            cls.setMochiConfig(objectMapper.writeValueAsString(validated));
        } catch (JsonProcessingException e) {
            throw new BusinessException("Could not serialize mochi-config", 400);
        }
        classRepo.save(cls);
        log.info("[Class] updated mochi-config class={} org={}", classId, orgId);
        return ResponseEntity.ok(ApiResponse.success(validated));
    }

    // ── Set the class teaching style (per-class teacher instruction) ───────────

    /**
     * Owner-gated. Stores the teacher's teaching instruction on the class corpus
     * avatar's {@code teacherPreferences} — already injected into the tutor system
     * prompt (## TEACHER INSTRUCTIONS), so it applies to every student's Mochi in
     * the class. The student-facing /avatars/{id}/teacher-preferences path rejects
     * class avatars (403), so centre edits must come through this owner-gated route.
     */
    @PatchMapping("/classes/{classId}/teaching-style")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateTeachingStyle(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, String> body) {
        accessService.ensureOwner(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);
        if (cls.getCorpusAvatarId() == null) {
            throw new BusinessException("This class has no content corpus yet.", 400);
        }
        String prefs = body == null ? "" : body.getOrDefault("teacherPreferences", "");
        if (prefs != null && prefs.length() > 500) {
            throw new BusinessException("Teaching style must be under 500 characters", 400);
        }
        Avatar corpus = avatarRepository.findById(cls.getCorpusAvatarId())
                .orElseThrow(() -> new BusinessException("Corpus avatar not found", 404));
        corpus.setTeacherPreferences(prefs == null || prefs.isBlank() ? null : prefs.strip());
        avatarRepository.save(corpus);
        log.info("[Class] updated teaching-style class={} org={} set={}",
                classId, orgId, corpus.getTeacherPreferences() != null);
        Map<String, Object> out = new HashMap<>();
        out.put("teacherPreferences", corpus.getTeacherPreferences() == null
                ? "" : corpus.getTeacherPreferences());
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Centre members (all users in the org + their class memberships) ────────

    @GetMapping("/members")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> members(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        accessService.ensureOwner(userId, orgId);
        // Map classId → class name for labelling memberships.
        Map<String, String> classNames = new HashMap<>();
        for (OrgClassJpaEntity c : classRepo.findByOrganizationId(orgId)) {
            classNames.put(c.getId(), c.getName());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (UserJpaEntity u : userRepo.findByCentreId(orgId)) {
            List<Map<String, Object>> classes = new ArrayList<>();
            for (ClassMembershipJpaEntity m : membershipRepo.findByUserId(u.getId())) {
                if (!ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus())) continue;
                if (!classNames.containsKey(m.getClassId())) continue; // class in another org
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
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Assign a member to a class (provisions a branded centre avatar) ────────

    @PostMapping("/classes/{classId}/members")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> assign(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @RequestBody Map<String, Object> body) {
        accessService.ensureOwner(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);
        String studentId = str(body.get("userId"));
        if (studentId == null || studentId.isBlank())
            throw new BusinessException("userId is required", 400);

        UserJpaEntity student = userRepo.findById(studentId)
                .orElseThrow(() -> new BusinessException("Student not found", 404));
        if (!orgId.equals(student.getCentreId()))
            throw new BusinessException("Student is not a member of this centre", 403);

        // Provision (idempotently) the student's branded class avatar + membership.
        String avatarId = classEnrollmentService.enroll(cls, studentId);

        log.info("[Class] assigned student={} class={} avatar={}", studentId, classId, avatarId);
        return ResponseEntity.ok(ApiResponse.success(Map.of(
                "avatarId", avatarId, "classId", classId, "userId", studentId)));
    }

    // ── Remove a member (locks their avatar; keeps history) ───────────────────

    @DeleteMapping("/classes/{classId}/members/{studentId}")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> remove(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String studentId) {
        accessService.ensureOwner(userId, orgId);
        requireClass(orgId, classId);
        ClassMembershipJpaEntity m = membershipRepo.findByClassIdAndUserId(classId, studentId)
                .orElseThrow(() -> new BusinessException("Membership not found", 404));
        m.setStatus(ClassMembershipJpaEntity.STATUS_REMOVED);
        membershipRepo.save(m);
        // Remove them from the class's CLASS group.
        classGroupService.syncStudentLeave(classId, studentId);
        if (m.getStudentAvatarId() != null) {
            avatarRepository.findById(m.getStudentAvatarId()).ifPresent(a -> {
                a.lockAvatar();
                avatarRepository.save(a);
            });
        }
        return ResponseEntity.ok(ApiResponse.success(Map.of("removed", true)));
    }

    // ── Class roster ──────────────────────────────────────────────────────────

    @GetMapping("/classes/{classId}/members")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> roster(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
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
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Class analytics: roster with grasp (quiz accuracy) ─────────────────────
    // Grouped by avatars.class_id so a student in multiple classes contributes to
    // each independently. Distinct path from the cohort-based heatmap to avoid an
    // ambiguous mapping with CentreAnalyticsController.

    @GetMapping("/classes/{classId}/analytics/roster")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> classRosterAnalytics(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
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
        return ResponseEntity.ok(ApiResponse.success(out));
    }

    // ── Class analytics: topic × student grasp heatmap ─────────────────────────

    @GetMapping("/classes/{classId}/analytics/heatmap")
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponse<Map<String, Object>>> classHeatmap(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId) {
        accessService.ensureOwner(userId, orgId);
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
            return ResponseEntity.ok(ApiResponse.success(empty));
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
                .sorted(java.util.Comparator.comparing(id -> studentNames.getOrDefault(id, "")))
                .limit(HEATMAP_MAX_STUDENTS)
                .collect(java.util.stream.Collectors.toList());
        List<String> topics = topicAttempts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(HEATMAP_MAX_TOPICS)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(java.util.stream.Collectors.toList());

        List<Map<String, Object>> studentDtos = studentIds.stream().map(id -> {
            String name = studentNames.getOrDefault(id, "");
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", id);
            dto.put("displayName", name);
            dto.put("initials", toInitials(name));
            return dto;
        }).collect(java.util.stream.Collectors.toList());

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
        }).collect(java.util.stream.Collectors.toList());
        List<Map<String, Object>> weakest = topicAverages.stream()
                .sorted(java.util.Comparator.comparingDouble(m -> (Double) m.get("avg")))
                .limit(5)
                .collect(java.util.stream.Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("students", studentDtos);
        result.put("topics", topics);
        result.put("cells", cells);
        result.put("topicAverages", topicAverages);
        result.put("weakest", weakest);
        log.info("[Class] heatmap class={} students={} topics={}", classId, studentIds.size(), topics.size());
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private OrgClassJpaEntity requireClass(String orgId, String classId) {
        OrgClassJpaEntity cls = classRepo.findById(classId)
                .orElseThrow(() -> new BusinessException("Class not found", 404));
        if (!orgId.equals(cls.getOrganizationId()))
            throw new BusinessException("Class not in this organization", 403);
        return cls;
    }

    private void applyClassConfig(Avatar a, OrgClassJpaEntity cls) {
        a.setCentreBrandName(cls.getBrandName() != null && !cls.getBrandName().isBlank()
                ? cls.getBrandName() : cls.getName());
        a.setCentreAccentColor(cls.getAccentColor());
        a.setCosmeticEyewear(cls.getCosmeticEyewear());
        a.setCosmeticClothes(cls.getCosmeticClothes());
        a.setCosmeticShoes(cls.getCosmeticShoes());
    }

    private Map<String, Object> toDto(OrgClassJpaEntity c, long studentCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("name", c.getName());
        m.put("subject", c.getSubject());
        m.put("level", c.getLevel());
        m.put("joinCode", c.getJoinCode());
        m.put("corpusAvatarId", c.getCorpusAvatarId());
        m.put("characterType", c.getCharacterType());
        m.put("brandName", c.getBrandName());
        m.put("accentColor", c.getAccentColor());
        m.put("examDate", c.getExamDate() == null ? null : c.getExamDate().toString());
        m.put("cosmeticEyewear", c.getCosmeticEyewear());
        m.put("cosmeticClothes", c.getCosmeticClothes());
        m.put("cosmeticShoes", c.getCosmeticShoes());
        m.put("mochiConfig", deserializeMochiConfig(c.getMochiConfig()));
        m.put("studentCount", studentCount);
        return m;
    }

    /** Deserialize the stored TEXT blob to a MochiConfig; null when unset/corrupt. */
    private MochiConfig deserializeMochiConfig(String blob) {
        if (blob == null || blob.isBlank()) return null;
        try {
            return objectMapper.readValue(blob, MochiConfig.class);
        } catch (JsonProcessingException e) {
            log.warn("[Class] could not deserialize mochi_config blob: {}", e.getMessage());
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private CharacterType parseCharacter(String s) {
        if (s == null || s.isBlank()) return CharacterType.MOCHI;
        try {
            return CharacterType.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CharacterType.MOCHI;
        }
    }

    // ── Backfill CLASS groups for existing classes (one-time, idempotent) ──────

    @PostMapping("/classes/backfill-groups")
    @Transactional
    public ResponseEntity<ApiResponse<Map<String, Object>>> backfillClassGroups(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId) {
        accessService.ensureOwner(userId, orgId);
        int created = 0;
        for (OrgClassJpaEntity cls : classRepo.findByOrganizationId(orgId)) {
            boolean existed = classGroupService.hasClassGroup(cls.getId());
            classGroupService.ensureClassGroup(cls);
            if (!existed) created++;
            for (ClassMembershipJpaEntity m : membershipRepo.findByClassId(cls.getId())) {
                if (ClassMembershipJpaEntity.STATUS_ACTIVE.equals(m.getStatus())) {
                    classGroupService.syncStudentJoin(cls, m.getUserId());
                }
            }
        }
        log.info("[Class] backfilled {} CLASS groups for org={}", created, orgId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("groupsCreated", created)));
    }

    // ── Centre narration ───────────────────────────────────────────────────

    /**
     * Triggers async narration generation for a class module.
     * Centre access check ensures only admins/teachers can generate narration.
     */
    @PostMapping("/classes/{classId}/modules/{moduleId}/narration/generate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> generateClassNarration(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String moduleId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        accessService.ensureOwner(userId, orgId);

        String voiceId = (body != null) ? body.getOrDefault("voiceId", "default") : "default";
        log.info("[Narration] Centre generate request user={} class={} module={} voice={}",
                userId, classId, moduleId, voiceId);

        String narrationId = narrationService.generateAsync(moduleId, voiceId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("narrationId", narrationId);
        response.put("status", "GENERATING");

        return ResponseEntity.status(org.springframework.http.HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(response, null, 202));
    }

    /**
     * Returns narration status and segments for a class module.
     */
    @GetMapping("/classes/{classId}/modules/{moduleId}/narration")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getClassNarration(
            @AuthenticationPrincipal String userId,
            @PathVariable String orgId,
            @PathVariable String classId,
            @PathVariable String moduleId
    ) {
        accessService.ensureOwner(userId, orgId);

        return narrationService.get(moduleId)
                .map(n -> {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("id", n.getId());
                    resp.put("status", n.getStatus());
                    resp.put("voiceId", n.getVoiceId());
                    resp.put("totalDurationMs", n.getTotalDurationMs());
                    try {
                        resp.put("segments", new com.fasterxml.jackson.databind.ObjectMapper()
                                .readValue(n.getSegmentsJson(), List.class));
                    } catch (Exception e) {
                        resp.put("segments", List.of());
                    }
                    return ResponseEntity.ok(ApiResponse.success(resp));
                })
                .orElseGet(() -> ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("Narration not found for this module", 404)));
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private Subject parseSubject(String s) {
        if (s == null || s.isBlank()) return Subject.GENERAL;
        try {
            return Subject.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Subject.GENERAL;
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String generateCode() {
        for (int attempt = 0; attempt < 6; attempt++) {
            char[] buf = new char[CODE_LEN];
            for (int i = 0; i < CODE_LEN; i++) buf[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
            String c = new String(buf);
            if (classRepo.findByJoinCode(c).isEmpty()) return c;
        }
        throw new BusinessException("Could not allocate join code — try again", 503);
    }
}
