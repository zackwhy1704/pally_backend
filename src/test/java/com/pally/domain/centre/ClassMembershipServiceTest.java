package com.pally.domain.centre;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.group.ClassGroupService;
import com.pally.domain.organization.ClassEnrollmentService;
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

@ExtendWith(MockitoExtension.class)
class ClassMembershipServiceTest {

    @Mock CentreAccessService accessService;
    @Mock OrgClassJpaRepository classRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock UserJpaRepository userRepo;
    @Mock AvatarRepository avatarRepository;
    @Mock QuizQuestionResultJpaRepository quizResultRepo;
    @Mock ClassGroupService classGroupService;
    @Mock ClassEnrollmentService classEnrollmentService;

    @InjectMocks ClassMembershipService service;

    private static final String OWNER_ID   = "owner-1";
    private static final String ORG_ID     = "org-1";
    private static final String CLASS_ID   = "class-1";
    private static final String STUDENT_ID = "student-1";

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
        cls.setJoinCode("ABCD2345");
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

    // ── assign ────────────────────────────────────────────────────────────

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
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
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

    // ── remove ────────────────────────────────────────────────────────────

    @Test
    void remove_delegatesToEnrollmentLeave_andDoesNotSoftLockAvatar() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setId("m-1");
        m.setClassId(CLASS_ID);
        m.setUserId(STUDENT_ID);
        m.setStudentAvatarId("avatar-1");
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.of(m));
        // No remaining memberships after leave.
        when(membershipRepo.findByUserId(STUDENT_ID)).thenReturn(List.of());

        service.remove(OWNER_ID, ORG_ID, CLASS_ID, STUDENT_ID);

        // Hard-delete path (mirrors student leave) — not a soft-lock (INV-3).
        verify(classEnrollmentService).leave(cls, STUDENT_ID);
        verify(avatarRepository, never()).save(any());
        verify(membershipRepo, never()).save(any());
    }

    @Test
    void remove_lastClassInCentre_clearsCentreId() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setClassId(CLASS_ID);
        m.setUserId(STUDENT_ID);
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.of(m));
        when(membershipRepo.findByUserId(STUDENT_ID)).thenReturn(List.of());
        UserJpaEntity u = new UserJpaEntity();
        u.setId(STUDENT_ID);
        u.setCentreId(ORG_ID);
        u.setCohortLabel("P4 Math");
        when(userRepo.findById(STUDENT_ID)).thenReturn(Optional.of(u));

        service.remove(OWNER_ID, ORG_ID, CLASS_ID, STUDENT_ID);

        assertThat(u.getCentreId()).isNull();
        assertThat(u.getCohortLabel()).isNull();
        verify(userRepo).save(u);
    }

    @Test
    void remove_otherActiveClassInSameCentre_keepsCentreId() {
        OrgClassJpaEntity cls = classEntity();
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(cls));
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setClassId(CLASS_ID);
        m.setUserId(STUDENT_ID);
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.of(m));
        // Second active class still in the same org.
        ClassMembershipJpaEntity other = new ClassMembershipJpaEntity();
        other.setClassId("class-2");
        other.setUserId(STUDENT_ID);
        other.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByUserId(STUDENT_ID)).thenReturn(List.of(other));
        OrgClassJpaEntity cls2 = new OrgClassJpaEntity();
        cls2.setId("class-2");
        cls2.setOrganizationId(ORG_ID);
        when(classRepo.findById("class-2")).thenReturn(Optional.of(cls2));

        service.remove(OWNER_ID, ORG_ID, CLASS_ID, STUDENT_ID);

        verify(userRepo, never()).save(any());
    }

    @Test
    void remove_alreadyRemovedMembership_isIdempotentAndDoesNotCallLeaveAgain() {
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(classEntity()));
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setClassId(CLASS_ID);
        m.setUserId(STUDENT_ID);
        m.setStatus(ClassMembershipJpaEntity.STATUS_REMOVED);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.of(m));

        Map<String, Object> result = service.remove(OWNER_ID, ORG_ID, CLASS_ID, STUDENT_ID);

        assertThat(result.get("removed")).isEqualTo(true);
        verify(classEnrollmentService, never()).leave(any(), anyString());
    }

    // ── tenant isolation ─────────────────────────────────────────────────

    @Test
    void requireClass_classInAnotherOrg_isForbidden() {
        OrgClassJpaEntity foreign = classEntity();
        foreign.setOrganizationId("other-org");
        when(classRepo.findById(CLASS_ID)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.roster(OWNER_ID, ORG_ID, CLASS_ID))
                .isInstanceOf(BusinessException.class);
    }

    // ── analytics ─────────────────────────────────────────────────────────

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
}
