package com.pally.domain.centre;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.centre.dto.MochiConfig;
import com.pally.domain.group.ClassGroupService;
import com.pally.domain.module.NarrationService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
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
 * Class CRUD: create/list/update/delete + Mochi config + teaching style + narration + groups.
 * Split from the original ClassService (692-line god-service) to stay ≤8 deps.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClassCrudService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LEN = 8;

    private final CentreAccessService accessService;
    private final OrgClassJpaRepository classRepo;
    private final ClassMembershipJpaRepository membershipRepo;
    private final AvatarRepository avatarRepository;
    private final ClassGroupService classGroupService;
    private final ObjectMapper objectMapper;
    private final NarrationService narrationService;
    private final OrganizationJpaRepository orgRepo;

    // ── Public lookup (used by controller brief delegation) ───────────────────

    /** Returns the class entity after verifying it belongs to orgId; throws 404/403 otherwise. */
    public OrgClassJpaEntity getClass(String orgId, String classId) {
        return requireClass(orgId, classId);
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> createClass(String userId, String orgId, Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);

        String name = str(body.get("name"));
        if (name == null || name.isBlank()) throw new BusinessException("name is required", 400);

        // B2B class-limit enforcement — check before creating any resources.
        OrganizationJpaEntity org = orgRepo.findById(orgId)
                .orElseThrow(() -> new BusinessException("Organisation not found", 404));
        long activeClasses = classRepo.findByOrganizationId(orgId).stream().count();
        if (org.getClassLimit() > 0 && activeClasses >= org.getClassLimit()) {
            throw new BusinessException(
                    "Class limit reached — add class licenses to create more classes.", 403);
        }

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

        Avatar corpus = Avatar.create(
                userId, name + " Corpus",
                parseSubject(cls.getSubject()),
                parseCharacter(cls.getCharacterType()),
                cls.getLevel(), null);
        corpus.markCentreClassAvatar();
        // Bind the corpus avatar to its class BEFORE saving (cls.id is already set
        // above). Without this the avatar's classId stays null, so every module
        // generated from this corpus is tagged class_id=NULL and classModules()
        // returns nothing — the "No modules yet" orphaning bug.
        corpus.setClassId(cls.getId());
        Avatar savedCorpus = avatarRepository.save(corpus);
        cls.setCorpusAvatarId(savedCorpus.getId());
        try {
            cls.setMochiConfig(objectMapper.writeValueAsString(defaultMochiConfig(cls.getId())));
        } catch (JsonProcessingException e) {
            log.warn("[Class] could not default mochi-config for {}: {}", cls.getId(), e.getMessage());
        }
        classRepo.save(cls);
        classGroupService.ensureClassGroup(cls);
        log.info("[Class] created class={} org={} code={}", cls.getId(), orgId, cls.getJoinCode());
        return toDto(cls, 0);
    }

    // ── List ──────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listClasses(String userId, String orgId) {
        accessService.ensureStaff(userId, orgId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (OrgClassJpaEntity cls : classRepo.findByOrganizationId(orgId)) {
            long count = membershipRepo.countByClassIdAndStatus(
                    cls.getId(), ClassMembershipJpaEntity.STATUS_ACTIVE);
            out.add(toDto(cls, count));
        }
        return out;
    }

    // ── Update ────────────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> updateClass(
            String userId, String orgId, String classId, Map<String, Object> body) {
        accessService.ensureStaff(userId, orgId);
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

    // ── Delete ────────────────────────────────────────────────────────────────

    @Transactional
    public void deleteClass(String userId, String orgId, String classId) {
        accessService.ensureOwner(userId, orgId);
        OrgClassJpaEntity cls = requireClass(orgId, classId);

        for (ClassMembershipJpaEntity m : membershipRepo.findByClassId(classId)) {
            if (m.getStudentAvatarId() != null) {
                avatarRepository.deleteById(m.getStudentAvatarId());
            }
        }
        if (cls.getCorpusAvatarId() != null) {
            avatarRepository.deleteById(cls.getCorpusAvatarId());
        }
        classGroupService.deleteClassGroup(classId);
        classRepo.delete(cls);
        log.info("[Class] deleted class={} org={}", classId, orgId);
    }

    // ── Mochi config ──────────────────────────────────────────────────────────

    @Transactional
    public MochiConfig updateMochiConfig(
            String userId, String orgId, String classId, MochiConfig config) {
        accessService.ensureStaff(userId, orgId);
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

    // ── Teaching style ────────────────────────────────────────────────────────

    @Transactional
    public Map<String, Object> updateTeachingStyle(
            String userId, String orgId, String classId, Map<String, String> body) {
        accessService.ensureStaff(userId, orgId);
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

    // ── Narration ─────────────────────────────────────────────────────────────

    public String generateClassNarration(
            String userId, String orgId, String classId, String moduleId, Map<String, String> body) {
        accessService.ensureStaff(userId, orgId);
        String voiceId = (body != null) ? body.getOrDefault("voiceId", "default") : "default";
        log.info("[Narration] Centre generate request user={} class={} module={} voice={}",
                userId, classId, moduleId, voiceId);
        return narrationService.generateAsync(moduleId, voiceId);
    }

    public Optional<Map<String, Object>> getClassNarration(
            String userId, String orgId, String classId, String moduleId) {
        accessService.ensureStaff(userId, orgId);
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

    // ── Backfill groups (idempotent) ──────────────────────────────────────────

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

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    Map<String, Object> toDto(OrgClassJpaEntity c, long studentCount) {
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

    private MochiConfig deserializeMochiConfig(String blob) {
        if (blob == null || blob.isBlank()) return null;
        try {
            return objectMapper.readValue(blob, MochiConfig.class);
        } catch (JsonProcessingException e) {
            log.warn("[Class] could not deserialize mochi_config blob: {}", e.getMessage());
            return null;
        }
    }

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
