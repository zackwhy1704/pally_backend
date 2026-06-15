package com.pally.domain.centre;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.centre.dto.MochiConfig;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.CentreAccessService;
import com.pally.domain.group.ClassGroupService;
import com.pally.domain.module.NarrationService;
import com.pally.domain.organization.ClassEnrollmentService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application service for centre classes — owns all class business logic so the
 * {@link ClassController} stays a thin HTTP delegator (parse → call → wrap).
 *
 * <p>Each method is owner-gated through {@link CentreAccessService} and returns
 * the exact response shapes the controller used to build inline, so the HTTP
 * contract is byte-identical. Repository access stays here (matching the
 * existing service style, e.g. {@code ChallengeService}); migrating these JPA
 * dependencies to domain ports is deferred to the hexagonal phase.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LEN = 8;

    // Heatmap legibility caps (mirror CentreAnalyticsController).
    private static final int HEATMAP_MAX_STUDENTS = 40;
    private static final int HEATMAP_MAX_TOPICS = 20;

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

    // ── Create a class ────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createClass(String userId, String orgId, Map<String, Object> body) {
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
        // Default a deterministic, VISUALLY DISTINCT Mochi look so every class is
        // identifiable at a glance even if the teacher never opens the picker —
        // body colour + a real accessory + an aura, all derived from the class id.
        try {
            cls.setMochiConfig(objectMapper.writeValueAsString(
                    defaultMochiConfig(cls.getId())));
        } catch (JsonProcessingException e) {
            log.warn("[Class] could not default mochi-config for {}: {}",
                    cls.getId(), e.getMessage());
        }
        classRepo.save(cls);
        // Auto-create the class's CLASS group (membership syncs from enrolment;
        // the centre owner sits in it as TEACHER).
        classGroupService.ensureClassGroup(cls);
        log.info("[Class] created class={} org={} code={}", cls.getId(), orgId, cls.getJoinCode());
        return toDto(cls, 0);
    }

    // ── List classes ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listClasses(String userId, String orgId) {
        accessService.ensureOwner(userId, orgId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrgClassJpaEntity cls : classRepo.findByOrganizationId(orgId)) {
            long count = membershipRepo.countByClassIdAndStatus(
                    cls.getId(), ClassMembershipJpaEntity.STATUS_ACTIVE);
            out.add(toDto(cls, count));
        }
        return out;
    }

    // ── Edit a class (name/brand/character/level/accent/exam/cosmetics) ────────

    @Transactional
    public Map<String, Object> updateClass(
            String userId, String orgId, String classId, Map<String, Object> body) {
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
        return toDto(cls, count);
    }

    // ── Delete a class ─────────────────────────────────────────────────────────

    /**
     * Owner-gated. Permanently deletes a class and everything provisioned with it:
     * each enrolled student's class avatar, the hidden corpus avatar (their wiki /
     * files / chat cascade on avatar delete), and the CLASS study group. The class
     * row delete cascades class_membership + assignment via their FK constraints.
     */
    @Transactional
    public void deleteClass(String userId, String orgId, String classId) {
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
    }

    // ── Set the class Mochi customization config ───────────────────────────────

    /**
     * Owner-gated. Validates the rich MochiConfig, serializes it to JSON and stores
     * it in {@code org_class.mochi_config} (TEXT). Returns the saved MochiConfig.
     */
    @Transactional
    public MochiConfig updateMochiConfig(
            String userId, String orgId, String classId, MochiConfig config) {
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
        return validated;
    }

    // ── Set the class teaching style (per-class teacher instruction) ───────────

    /**
     * Owner-gated. Stores the teacher's teaching instruction on the class corpus
     * avatar's {@code teacherPreferences} — already injected into the tutor system
     * prompt (## TEACHER INSTRUCTIONS), so it applies to every student's Mochi in
     * the class.
     */
    @Transactional
    public Map<String, Object> updateTeachingStyle(
            String userId, String orgId, String classId, Map<String, String> body) {
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
        return out;
    }

    // ── Centre members (all users in the org + their class memberships) ────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> members(String userId, String orgId) {
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
        return out;
    }

    // ── Assign a member to a class (provisions a branded centre avatar) ────────

    @Transactional
    public Map<String, Object> assign(
            String userId, String orgId, String classId, Map<String, Object> body) {
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
        return Map.of("avatarId", avatarId, "classId", classId, "userId", studentId);
    }

    // ── Remove a member (locks their avatar; keeps history) ───────────────────

    @Transactional
    public Map<String, Object> remove(String userId, String orgId, String classId, String studentId) {
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
        return Map.of("removed", true);
    }

    // ── Class roster ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> roster(String userId, String orgId, String classId) {
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
        return out;
    }

    // ── Class analytics: roster with grasp (quiz accuracy) ─────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> classRosterAnalytics(String userId, String orgId, String classId) {
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
        return out;
    }

    // ── Class analytics: topic × student grasp heatmap ─────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> classHeatmap(String userId, String orgId, String classId) {
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
        return result;
    }

    // ── Backfill CLASS groups for existing classes (one-time, idempotent) ──────

    @Transactional
    public Map<String, Object> backfillClassGroups(String userId, String orgId) {
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
        return Map.of("groupsCreated", created);
    }

    // ── Centre narration ───────────────────────────────────────────────────

    /**
     * Triggers async narration generation for a class module. Owner-gated.
     * Returns the narration id (the controller wraps it in the 202 response).
     */
    public String generateClassNarration(
            String userId, String orgId, String classId, String moduleId, Map<String, String> body) {
        accessService.ensureOwner(userId, orgId);
        String voiceId = (body != null) ? body.getOrDefault("voiceId", "default") : "default";
        log.info("[Narration] Centre generate request user={} class={} module={} voice={}",
                userId, classId, moduleId, voiceId);
        return narrationService.generateAsync(moduleId, voiceId);
    }

    /**
     * Returns narration status and segments for a class module, or empty when the
     * module has no narration yet (the controller maps empty → 404). Owner-gated.
     */
    public Optional<Map<String, Object>> getClassNarration(
            String userId, String orgId, String classId, String moduleId) {
        accessService.ensureOwner(userId, orgId);
        return narrationService.get(moduleId).map(n -> {
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("id", n.getId());
            resp.put("status", n.getStatus());
            resp.put("voiceId", n.getVoiceId());
            resp.put("totalDurationMs", n.getTotalDurationMs());
            try {
                resp.put("segments", new ObjectMapper().readValue(n.getSegmentsJson(), List.class));
            } catch (Exception e) {
                resp.put("segments", List.of());
            }
            return resp;
        });
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

    // Distinct, accessory-bearing default look per class so kids can tell their
    // classes apart at a glance (a body-hue-only default reads as "no
    // customisation"). Deterministic from the class id; teachers can override.
    private static final String[] DEFAULT_ACCESSORIES =
            {"bow", "cap", "glasses", "crown", "headband"};
    private static final String[] DEFAULT_AURAS =
            {"sparkle", "fire", "chill", "electric", "bloom"};

    private static MochiConfig defaultMochiConfig(String classId) {
        int h = Math.abs(classId.hashCode());
        int body = h % (MochiConfig.BODY_VARIANT_MAX + 1);
        String accessory = DEFAULT_ACCESSORIES[(h / 12) % DEFAULT_ACCESSORIES.length];
        String aura = DEFAULT_AURAS[(h / 60) % DEFAULT_AURAS.length];
        return new MochiConfig(body, accessory, aura);
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
