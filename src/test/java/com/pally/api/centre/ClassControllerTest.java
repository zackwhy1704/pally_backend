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
import com.pally.shared.response.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

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
 * Unit tests for {@link ClassController}. The invariants below are the contract
 * the centre product depends on: classes provision a hidden corpus avatar,
 * assignment provisions a branded closed-book student avatar bound to the class
 * corpus (idempotently), removal locks the avatar, and every route is owner-gated
 * with tenant isolation.
 */
@ExtendWith(MockitoExtension.class)
class ClassControllerTest {

    @Mock CentreAccessService accessService;
    @Mock OrgClassJpaRepository classRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock UserJpaRepository userRepo;
    @Mock AvatarRepository avatarRepository;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;

    @InjectMocks ClassController controller;

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
        // save returns the same avatar so the controller can read its generated id.
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
        cls.setCharacterType("ATWSAKURA");
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

        ResponseEntity<ApiResponse<Map<String, Object>>> resp = controller.createClass(
                OWNER_ID, ORG_ID,
                Map.of("name", "P4 Math", "subject", "MATHS", "level", "P4",
                        "characterType", "ATWSAKURA"));

        Map<String, Object> body = resp.getBody().data();
        assertThat(body.get("name")).isEqualTo("P4 Math");
        assertThat((String) body.get("joinCode")).hasSize(8);
        assertThat(body.get("corpusAvatarId")).isNotNull();
        // One avatar saved (the corpus); the class itself is saved via classRepo.
        verify(avatarRepository).save(any(Avatar.class));
        verify(classRepo).save(any(OrgClassJpaEntity.class));
    }

    @Test
    void createClass_blankName_isRejected() {
        assertThatThrownBy(() -> controller.createClass(
                OWNER_ID, ORG_ID, Map.of("name", "  ")))
                .isInstanceOf(BusinessException.class);
        verify(avatarRepository, never()).save(any());
    }

    // ── assign ───────────────────────────────────────────────────────────────

    @Test
    void assign_provisionsBrandedClosedBookAvatar_boundToClassCorpus() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.of(student()));
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());

        ResponseEntity<ApiResponse<Map<String, Object>>> resp = controller.assign(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("userId", STUDENT_ID));

        ArgumentCaptor<Avatar> captor = ArgumentCaptor.forClass(Avatar.class);
        verify(avatarRepository).save(captor.capture());
        Avatar provisioned = captor.getValue();
        assertThat(provisioned.isCentreAvatar()).isTrue();
        assertThat(provisioned.isAvatarLocked()).isFalse();
        assertThat(provisioned.getClassId()).isEqualTo(CLASS_ID);
        assertThat(provisioned.getCorpusAvatarId()).isEqualTo("corpus-1");
        assertThat(provisioned.getCentreBrandName()).isEqualTo("ABC P4 Math");
        assertThat(provisioned.getUserId()).isEqualTo(STUDENT_ID);
        verify(membershipRepo).save(any(ClassMembershipJpaEntity.class));
        assertThat(resp.getBody().data().get("classId")).isEqualTo(CLASS_ID);
    }

    @Test
    void assign_idempotent_returnsExistingAvatar_withoutReprovisioning() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.of(student()));
        ClassMembershipJpaEntity existing = new ClassMembershipJpaEntity();
        existing.setId("m-1");
        existing.setClassId(CLASS_ID);
        existing.setUserId(STUDENT_ID);
        existing.setStudentAvatarId("avatar-existing");
        existing.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.of(existing));

        ResponseEntity<ApiResponse<Map<String, Object>>> resp = controller.assign(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("userId", STUDENT_ID));

        assertThat(resp.getBody().data().get("avatarId")).isEqualTo("avatar-existing");
        verify(avatarRepository, never()).save(any());
    }

    @Test
    void assign_studentFromAnotherCentre_isForbidden() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        UserJpaEntity outsider = student();
        outsider.setCentreId("other-org");
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.of(outsider));

        assertThatThrownBy(() -> controller.assign(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("userId", STUDENT_ID)))
                .isInstanceOf(BusinessException.class);
        verify(avatarRepository, never()).save(any());
    }

    @Test
    void assign_unknownStudent_is404() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.assign(
                OWNER_ID, ORG_ID, CLASS_ID, Map.of("userId", STUDENT_ID)))
                .isInstanceOf(BusinessException.class);
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

        controller.remove(OWNER_ID, ORG_ID, CLASS_ID, STUDENT_ID);

        assertThat(m.getStatus()).isEqualTo(ClassMembershipJpaEntity.STATUS_REMOVED);
        assertThat(avatar.isAvatarLocked()).isTrue();
        verify(avatarRepository).save(avatar);
    }

    // ── tenant isolation ─────────────────────────────────────────────────────

    @Test
    void requireClass_classInAnotherOrg_isForbidden() {
        OrgClassJpaEntity foreign = classEntity();
        foreign.setOrganizationId("other-org");
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> controller.roster(OWNER_ID, ORG_ID, CLASS_ID))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void classRosterAnalytics_emptyClass_returnsEmptyList() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        when(quizResultRepo.findStudentActivityByClass(CLASS_ID)).thenReturn(List.of());

        ResponseEntity<ApiResponse<List<Map<String, Object>>>> resp =
                controller.classRosterAnalytics(OWNER_ID, ORG_ID, CLASS_ID);

        assertThat(resp.getBody().data()).isEmpty();
    }

    @Test
    void classHeatmap_emptyClass_returnsValidEmptyShapes() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        when(quizResultRepo.findHeatmapDataByClass(CLASS_ID)).thenReturn(List.of());

        ResponseEntity<ApiResponse<Map<String, Object>>> resp =
                controller.classHeatmap(OWNER_ID, ORG_ID, CLASS_ID);

        Map<String, Object> body = resp.getBody().data();
        assertThat(body).containsKeys("students", "topics", "cells", "topicAverages", "weakest");
        assertThat((List<?>) body.get("students")).isEmpty();
    }
}
