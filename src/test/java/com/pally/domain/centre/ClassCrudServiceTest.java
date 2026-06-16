package com.pally.domain.centre;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.centre.dto.MochiConfig;
import com.pally.domain.group.ClassGroupService;
import com.pally.domain.module.NarrationService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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

@ExtendWith(MockitoExtension.class)
class ClassCrudServiceTest {

    @Mock CentreAccessService accessService;
    @Mock OrgClassJpaRepository classRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock AvatarRepository avatarRepository;
    @Mock ClassGroupService classGroupService;
    @Mock NarrationService narrationService;
    @Spy com.fasterxml.jackson.databind.ObjectMapper objectMapper =
            new com.fasterxml.jackson.databind.ObjectMapper();

    @InjectMocks ClassCrudService service;

    private static final String OWNER_ID = "owner-1";
    private static final String ORG_ID   = "org-1";
    private static final String CLASS_ID = "class-1";

    @BeforeEach
    void setUp() {
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId(ORG_ID);
        org.setOwnerUserId(OWNER_ID);
        lenient().when(accessService.ensureOwner(OWNER_ID, ORG_ID)).thenReturn(org);
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
        assertThat(body.get("mochiConfig")).isNotNull();
        verify(avatarRepository).save(any(Avatar.class));
        verify(classRepo).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void createClass_blankName_isRejected() {
        assertThatThrownBy(() -> service.createClass(OWNER_ID, ORG_ID, Map.of("name", "  ")))
                .isInstanceOf(BusinessException.class);
        verify(avatarRepository, never()).save(any());
    }

    // ── deleteClass ────────────────────────────────────────────────────────

    @Test
    void deleteClass_cascadesStudentAvatars_corpusAvatar_group_andClassRow() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setStudentAvatarId("avatar-1");
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

    // ── updateTeachingStyle ────────────────────────────────────────────────

    @Test
    void updateTeachingStyle_ownerGated_setsCorpusTeacherPreferences() {
        OrgClassJpaEntity cls = classEntity();
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
        assertThatThrownBy(() -> service.updateTeachingStyle(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("teacherPreferences", "x".repeat(501))))
                .isInstanceOf(BusinessException.class);
        verify(avatarRepository, never()).save(any(Avatar.class));
    }

    // ── updateMochiConfig ─────────────────────────────────────────────────

    @Test
    void updateMochiConfig_validConfig_persistsAndReturnsIt() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        MochiConfig cfg = new MochiConfig(3, "crown", "sparkle");

        MochiConfig result = service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, cfg);

        assertThat(result).isEqualTo(cfg);
        assertThat(cls.getMochiConfig()).contains("\"body\":3", "\"accessory\":\"crown\"");
        verify(classRepo).save(cls);
    }

    @Test
    void updateMochiConfig_thenList_returnsDeserializedConfig() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));
        MochiConfig cfg = new MochiConfig(5, "bow", "bloom");

        service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, cfg);
        List<Map<String, Object>> list = service.listClasses(OWNER_ID, ORG_ID);

        assertThat(list.get(0).get("mochiConfig")).isEqualTo(cfg);
    }

    @Test
    void listClasses_noConfigSet_mochiConfigIsNull() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));

        List<Map<String, Object>> list = service.listClasses(OWNER_ID, ORG_ID);

        assertThat(list.get(0).get("mochiConfig")).isNull();
    }

    @Test
    void updateMochiConfig_outOfRangeBodyVariant_isRejected() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        assertThatThrownBy(() ->
                service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID,
                        new MochiConfig(99, "crown", "sparkle")))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void updateMochiConfig_unknownAccessory_isRejected() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        assertThatThrownBy(() ->
                service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID,
                        new MochiConfig(3, "laser", "sparkle")))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void updateMochiConfig_unknownAura_isRejected() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        assertThatThrownBy(() ->
                service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID,
                        new MochiConfig(3, "crown", "rainbow")))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void deserializeMochiConfig_legacyBlobWithRemovedKeys_deserializesWithoutError() {
        OrgClassJpaEntity cls = classEntity();
        cls.setMochiConfig(
                "{\"body\":7,\"cheekVariant\":4,\"eyeStyle\":\"star\","
                        + "\"accessory\":\"cap\",\"aura\":\"fire\"}");
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));

        List<Map<String, Object>> list = service.listClasses(OWNER_ID, ORG_ID);

        MochiConfig mc = (MochiConfig) list.get(0).get("mochiConfig");
        assertThat(mc).isNotNull();
        assertThat(mc.body()).isEqualTo(7);
        assertThat(mc.accessory()).isEqualTo("cap");
        assertThat(mc.aura()).isEqualTo("fire");
    }

    @Test
    void updateMochiConfig_nonOwner_isForbidden() {
        when(accessService.ensureOwner("intruder", ORG_ID))
                .thenThrow(new BusinessException("Not the centre owner", 403));
        assertThatThrownBy(() ->
                service.updateMochiConfig("intruder", ORG_ID, CLASS_ID,
                        new MochiConfig(3, "crown", "sparkle")))
                .isInstanceOf(BusinessException.class);
        verify(classRepo, never()).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void mochiConfig_roundTrip_deserializesToSameValues() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(classRepo.findByOrganizationId(ORG_ID)).thenReturn(List.of(cls));
        MochiConfig original = new MochiConfig(11, "headband", "electric");

        service.updateMochiConfig(OWNER_ID, ORG_ID, CLASS_ID, original);
        MochiConfig roundTripped =
                (MochiConfig) service.listClasses(OWNER_ID, ORG_ID).get(0).get("mochiConfig");

        assertThat(roundTripped.body()).isEqualTo(11);
        assertThat(roundTripped.accessory()).isEqualTo("headband");
        assertThat(roundTripped.aura()).isEqualTo("electric");
    }
}
