package com.pally.api.centre;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.centre.CentreAccessService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClassService}. The invariants below are the contract
 * the centre product depends on: classes provision a hidden corpus avatar,
 * assignment provisions a branded closed-book student avatar bound to the class
 * corpus (idempotently), removal locks the avatar, and every operation is
 * owner-gated with tenant isolation. (Logic moved here from ClassController in
 * the controller→service refactor; the controller is now a thin delegator.)
 */
@ExtendWith(MockitoExtension.class)
class ClassServiceTest {

    @Mock CentreAccessService accessService;
    @Mock OrgClassJpaRepository classRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock UserJpaRepository userRepo;
    @Mock AvatarRepository avatarRepository;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;
    @Mock com.pally.domain.module.NarrationService narrationService;
    @Mock com.pally.domain.group.ClassGroupService classGroupService;
    @Mock com.pally.domain.organization.ClassEnrollmentService classEnrollmentService;
    // Real ObjectMapper — MochiConfig (de)serialization is the behaviour under test.
    @org.mockito.Spy com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks ClassService service;

    private static final String OWNER_ID = "owner-1";
    private static final String ORG_ID = "org-1";
    private static final String CLASS_ID = "class-1";
    private static final String STUDENT_ID = "student-1";

    private OrganizationJpaEntity org;

    @BeforeEach
    void setUp() {
        org = new OrganizationJpaEntity();
        org.setId(ORG_ID);
        org.setOwnerUserId(OWNER_ID);
        lenient().when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org);
        // save returns the same avatar so the service can read its generated id.
        lenient().when(avatarRepository.save(any(Avatar.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private OrgClassJpaEntity classEntity() {
        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setId(CLASS_ID);
        cls.setOrganizationId(ORG_ID);
        cls.setName("P4 Math");
        cls.setSubject("MATHS");
        cls.setLevel("P4");
        cls.setJoinCode("ABCD2345");
        cls.setCharacterType("MOCHI");
        cls.setBrandName("ABC P4 Math");
        cls.setAccentColor("#FF6BAE");
        cls.setCorpusAvatarId("corpus-1");
        return cls;
    }

    private UserJpaEntity student() {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(STUDENT_ID);
        u.setDisplayName("Alice Tan");
        u.setCentreId(ORG_ID);
        return u;
    }

    // ── createClass ────────────────────────────────────────────────────────

    @Test
    void createClass_provisionsCorpusAvatar_andReturnsJoinCode() {
        when(classRepo.findByJoinCode(anyString())).thenReturn(Optional.empty());

        Map<String, Object> body = service.createClass(
                OWNER_ID, ORG_ID,
                Map.of("name", "P4 Math", "subject", "MATHS", "level", "P4",
                        "characterType", "MOCHI"));

        assertThat(body.get("name")).isEqualTo("P4 Math");
        assertThat((String) body.get("joinCode")).hasSize(8);
        assertThat(body.get("corpusAvatarId")).isNotNull();
        // FIX 1: a default Mochi config is set on create so the class never
        // renders the plain fallback (body 0–11, accessory/aura none).
        assertThat(body.get("mochiConfig")).isNotNull();
        // One avatar saved (the corpus); the class itself is saved via classRepo.
        verify(avatarRepository).save(any(Avatar.class));
        verify(classRepo).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void createClass_blankName_isRejected() {
        assertThatThrownBy(() -> service.createClass(
                OWNER_ID, ORG_ID, Map.of("name", "  ")))
                .isInstanceOf(BusinessException.class);
        verify(avatarRepository, never()).save(any());
    }

    // ── assign ───────────────────────────────────────────────────────────────

    @Test
    void assign_delegatesToEnrollmentService_andReturnsAvatarId() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.of(student()));
        when(classEnrollmentService.enroll(cls, STUDENT_ID)).thenReturn("avatar-1");

        Map<String, Object> result = service.assign(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("userId", STUDENT_ID));

        verify(classEnrollmentService).enroll(cls, STUDENT_ID);
        assertThat(result.get("avatarId")).isEqualTo("avatar-1");
        assertThat(result.get("classId")).isEqualTo(CLASS_ID);
    }

    @Test
    void assign_studentFromAnotherCentre_isForbidden_andNotEnrolled() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        UserJpaEntity outsider = student();
        outsider.setCentreId("other-org");
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.of(outsider));

        assertThatThrownBy(() -> service.assign(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("userId", STUDENT_ID)))
                .isInstanceOf(BusinessException.class);
        verify(classEnrollmentService, never()).enroll(any(), anyString());
    }

    @Test
    void assign_unknownStudent_is404() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("userId", STUDENT_ID)))
                .isInstanceOf(BusinessException.class);
        verify(classEnrollmentService, never()).enroll(any(), anyString());
    }

    // ── remove ───────────────────────────────────────────────────────────────

    @Test
    void remove_locksAvatar_andMarksMembershipRemoved() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setId("m-1");
        m.setClassId(CLASS_ID);
        m.setUserId(STUDENT_ID);
        m.setStudentAvatarId("avatar-1");
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.of(m));
        Avatar avatar = Avatar.create(STUDENT_ID, "P4 Math", com.pally.domain.avatar.Subject.MATHS,
                com.pally.domain.avatar.CharacterType.MOCHI);
        when(avatarRepository.findById("avatar-1")).thenReturn(Optional.of(avatar));

        service.remove(OWNER_ID, ORG_ID, CLASS_ID, STUDENT_ID);

        assertThat(m.getStatus()).isEqualTo(ClassMembershipJpaEntity.STATUS_REMOVED);
        assertThat(avatar.isAvatarLocked()).isTrue();
        verify(avatarRepository).save(avatar);
    }

    // ── deleteClass ────────────────────────────────────────────────────────────

    @Test
    void deleteClass_cascadesStudentAvatars_corpusAvatar_group_andClassRow() {
        OrgClassJpaEntity cls = classEntity(); // corpusAvatarId = "corpus-1"
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setId("m-1");
        m.setClassId(CLASS_ID);
        m.setUserId(STUDENT_ID);
        m.setStudentAvatarId("avatar-1");
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassId(CLASS_ID)).thenReturn(List.of(m));

        service.deleteClass(OWNER_ID, ORG_ID, CLASS_ID);

        verify(avatarRepository).deleteById("avatar-1");
        verify(avatarRepository).deleteById("corpus-1");
        verify(classGroupService).deleteClassGroup(CLASS_ID);
        verify(classRepo).delete(cls);
    }

    @Test
    void deleteClass_classInAnotherOrg_isForbidden_andNothingDeleted() {
        OrgClassJpaEntity foreign = classEntity();
        foreign.setOrganizationId("other-org");
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.deleteClass(OWNER_ID, ORG_ID, CLASS_ID))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).delete(any(OrgClassJpaEntity.class));
        verify(avatarRepository, never()).deleteById(anyString());
    }

    // ── updateTeachingStyle ────────────────────────────────────────────────────

    @Test
    void updateTeachingStyle_ownerGated_setsCorpusTeacherPreferences() {
        OrgClassJpaEntity cls = classEntity(); // corpusAvatarId = "corpus-1"
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        Avatar corpus = Avatar.create("owner-1", "P4 Math Corpus",
                com.pally.domain.avatar.Subject.MATHS,
                com.pally.domain.avatar.CharacterType.MOCHI);
        when(avatarRepository.findById("corpus-1")).thenReturn(Optional.of(corpus));

        service.updateTeachingStyle(OWNER_ID, ORG_ID, CLASS_ID,
                Map.of("teacherPreferences", "Use more worked examples."));

        assertThat(corpus.getTeacherPreferences()).isEqualTo("Use more worked examples.");
        verify(avatarRepository).save(corpus);
    }

    @Test
    void updateTeachingStyle_over500Chars_is400() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        String tooLong = "x".repeat(501);

        assertThatThrownBy(() -> service.updateTeachingStyle(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("teacherPreferences", tooLong)))
                .isInstanceOf(BusinessException.class);
        verify(avatarRepository, never()).save(any(Avatar.class));
    }

    // ── tenant isolation ─────────────────────────────────────────────────────

    @Test
    void requireClass_classInAnotherOrg_isForbidden() {
        OrgClassJpaEntity foreign = classEntity();
        foreign.setOrganizationId("other-org");
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.roster(OWNER_ID, ORG_ID, CLASS_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void classRosterAnalytics_emptyClass_returnsEmptyList() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        when(quizResultRepo.findStudentActivityByClass(CLASS_ID)).thenReturn(List.of());

        List<Map<String, Object>> result =
                service.classRosterAnalytics(OWNER_ID, ORG_ID, CLASS_ID);

        assertThat(result).isEmpty();
    }

    @Test
    void classHeatmap_emptyClass_returnsValidEmptyShapes() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        when(quizResultRepo.findHeatmapDataByClass(CLASS_ID)).thenReturn(List.of());

        Map<String, Object> body = service.classHeatmap(OWNER_ID, ORG_ID, CLASS_ID);

        assertThat(body).containsKeys("students", "topics", "cells", "topicAverages", "weakest");
        assertThat((List<?>) body.get("students")).isEmpty();
    }

    // ── updateMochiConfig ──────────────────────────────────────────────────────

    @Test
    void updateMochiConfig_validConfig_persistsAndReturnsIt() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        com.pally.api.centre.dto.MochiConfig cfg =
                new com.pally.api.centre.dto.MochiConfig(3, "crown", "sparkle");

        com.pally.api.centre.dto.MochiConfig result =
                service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, cfg);

        // Returned config echoes the input.
        assertThat(result).isEqualTo(cfg);
        // Stored as a JSON TEXT blob on the entity, and the class is saved.
        assertThat(cls.getMochiConfig()).contains("\"body\":3", "\"accessory\":\"crown\"");
        verify(classRepo).save(cls);
    }

    @Test
    void updateMochiConfig_thenList_returnsDeserializedConfig() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));
        com.pally.api.centre.dto.MochiConfig cfg =
                new com.pally.api.centre.dto.MochiConfig(5, "bow", "bloom");

        service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, cfg);
        // The blob the PATCH stored is what the GET list will deserialize.
        List<Map<String, Object>> list = service.listClasses(OWNER_ID, ORG_ID);

        Object mc = list.get(0).get("mochiConfig");
        assertThat(mc).isEqualTo(cfg);
    }

    @Test
    void listClasses_noConfigSet_mochiConfigIsNull() {
        OrgClassJpaEntity cls = classEntity(); // mochiConfig stays null
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));

        List<Map<String, Object>> list = service.listClasses(OWNER_ID, ORG_ID);

        Map<String, Object> dto = list.get(0);
        assertThat(dto).containsKey("mochiConfig");
        assertThat(dto.get("mochiConfig")).isNull();
    }

    @Test
    void updateMochiConfig_outOfRangeBodyVariant_isRejected() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        com.pally.api.centre.dto.MochiConfig bad =
                new com.pally.api.centre.dto.MochiConfig(99, "crown", "sparkle");

        assertThatThrownBy(() ->
                service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, bad))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void updateMochiConfig_unknownAccessory_isRejected() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        com.pally.api.centre.dto.MochiConfig bad =
                new com.pally.api.centre.dto.MochiConfig(3, "laser", "sparkle");

        assertThatThrownBy(() ->
                service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, bad))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void updateMochiConfig_unknownAura_isRejected() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        com.pally.api.centre.dto.MochiConfig bad =
                new com.pally.api.centre.dto.MochiConfig(3, "crown", "rainbow");

        assertThatThrownBy(() ->
                service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, bad))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void deserializeMochiConfig_legacyBlobWithRemovedKeys_deserializesWithoutError() {
        // A blob saved before V74 simplification still carries eyeStyle/cheekVariant.
        // JsonIgnoreProperties(ignoreUnknown=true) must let it deserialize cleanly
        // into the new 3-field shape rather than 500-ing on old data.
        OrgClassJpaEntity cls = classEntity();
        cls.setMochiConfig(
                "{\"body\":7,\"cheekVariant\":4,\"eyeStyle\":\"star\","
                        + "\"accessory\":\"cap\",\"aura\":\"fire\"}");
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));

        List<Map<String, Object>> list = service.listClasses(OWNER_ID, ORG_ID);

        com.pally.api.centre.dto.MochiConfig mc =
                (com.pally.api.centre.dto.MochiConfig) list.get(0).get("mochiConfig");
        assertThat(mc).isNotNull();
        assertThat(mc.body()).isEqualTo(7);
        assertThat(mc.accessory()).isEqualTo("cap");
        assertThat(mc.aura()).isEqualTo("fire");
    }

    @Test
    void updateMochiConfig_nonOwner_isForbidden() {
        // ensureOwner throws for a non-owner; mochi-config must never persist.
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("Not the centre owner", 403));
        com.pally.api.centre.dto.MochiConfig cfg =
                new com.pally.api.centre.dto.MochiConfig(3, "crown", "sparkle");

        assertThatThrownBy(() ->
                service.updateMochiConfig("intruder", ORG_ID, CLASS_ID, cfg))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void mochiConfig_roundTrip_deserializesToSameValues() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));
        com.pally.api.centre.dto.MochiConfig original =
                new com.pally.api.centre.dto.MochiConfig(11, "headband", "electric");

        service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, original);
        com.pally.api.centre.dto.MochiConfig roundTripped =
                (com.pally.api.centre.dto.MochiConfig)
                        service.listClasses(OWNER_ID, ORG_ID).get(0).get("mochiConfig");

        assertThat(roundTripped.body()).isEqualTo(11);
        assertThat(roundTripped.accessory()).isEqualTo("headband");
        assertThat(roundTripped.aura()).isEqualTo("electric");
    }
}
