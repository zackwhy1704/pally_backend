package com.pally.domain.organization;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.group.ClassGroupService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassEnrollmentServiceTest {

    @Mock AvatarRepository avatarRepository;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock ClassGroupService classGroupService;
    @Mock OrganizationJpaRepository orgRepo;

    @InjectMocks ClassEnrollmentService service;

    private static final String CLASS_ID = "class-1";
    private static final String STUDENT_ID = "student-1";

    private OrgClassJpaEntity classEntity() {
        OrgClassJpaEntity cls = new OrgClassJpaEntity();
        cls.setId(CLASS_ID);
        cls.setOrganizationId("org-1");
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

    private OrganizationJpaEntity orgWithTerms() {
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId("org-1");
        org.setSubStatus(OrganizationJpaEntity.STATUS_PILOT);  // managed B2B
        org.setTermsAcceptedAt(Instant.now().minusSeconds(3600));
        org.setClassStudentCap(20);
        return org;
    }

    private OrganizationJpaEntity unmangedOrg() {
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId("org-1");
        org.setSubStatus(OrganizationJpaEntity.STATUS_NONE);  // non-B2B org — no gates apply
        return org;
    }

    @Test
    void enroll_provisionsBrandedClosedBookAvatar_boundToCorpus_andJoinsGroup() {
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(unmangedOrg()));
        when(avatarRepository.save(any(Avatar.class))).thenAnswer(inv -> inv.getArgument(0));

        service.enroll(cls, STUDENT_ID);

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
        verify(classGroupService).syncStudentJoin(cls, STUDENT_ID);
    }

    @Test
    void leave_deletesStudentAvatar_membership_andDropsFromGroup() {
        OrgClassJpaEntity cls = classEntity();
        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setId("m-1");
        m.setClassId(CLASS_ID);
        m.setUserId(STUDENT_ID);
        m.setStudentAvatarId("avatar-1");
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.of(m));

        service.leave(cls, STUDENT_ID);

        verify(avatarRepository).deleteById("avatar-1");
        verify(membershipRepo).delete(m);
        verify(classGroupService).syncStudentLeave(CLASS_ID, STUDENT_ID);
    }

    @Test
    void leave_notEnrolled_throws404_andDeletesNothing() {
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.leave(cls, STUDENT_ID))
                .isInstanceOf(com.pally.shared.exception.BusinessException.class);
        verify(avatarRepository, never()).deleteById(any());
        verify(membershipRepo, never()).delete(any());
    }

    @Test
    void enroll_idempotent_returnsExistingAvatar_withoutReprovisioning() {
        OrgClassJpaEntity cls = classEntity();
        ClassMembershipJpaEntity existing = new ClassMembershipJpaEntity();
        existing.setId("m-1");
        existing.setClassId(CLASS_ID);
        existing.setUserId(STUDENT_ID);
        existing.setStudentAvatarId("avatar-existing");
        existing.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID))
                .thenReturn(Optional.of(existing));

        String avatarId = service.enroll(cls, STUDENT_ID);

        assertThat(avatarId).isEqualTo("avatar-existing");
        verify(avatarRepository, never()).save(any());
        verify(membershipRepo, never()).save(any());
    }

    @Test
    void enroll_termsNotAccepted_throwsBusinessException() {
        // Only enforced for managed B2B orgs (PILOT or ACTIVE status)
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());

        OrganizationJpaEntity orgNoTerms = new OrganizationJpaEntity();
        orgNoTerms.setId("org-1");
        orgNoTerms.setSubStatus(OrganizationJpaEntity.STATUS_PILOT);  // managed B2B
        orgNoTerms.setTermsAcceptedAt(null);  // terms NOT accepted
        orgNoTerms.setClassStudentCap(20);
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(orgNoTerms));

        assertThatThrownBy(() -> service.enroll(cls, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("educator terms");

        verify(avatarRepository, never()).save(any());
    }

    @Test
    void enroll_pastStudentCap_throwsBusinessException() {
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());

        OrganizationJpaEntity orgFull = orgWithTerms(); // PILOT + terms accepted
        orgFull.setClassStudentCap(20);
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(orgFull));
        // Cap is checked via countByClassIdAndStatusAndRole (STUDENT only)
        when(membershipRepo.countByClassIdAndStatusAndRole(
                CLASS_ID, ClassMembershipJpaEntity.STATUS_ACTIVE, ClassMembershipJpaEntity.ROLE_STUDENT))
                .thenReturn(20L);

        assertThatThrownBy(() -> service.enroll(cls, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Class is full");

        verify(avatarRepository, never()).save(any());
    }

    @Test
    void enroll_staffSeatsDoNotCountAgainstStudentCap() {
        // If there are 20 active memberships but only 19 are STUDENT role,
        // a new STUDENT should be allowed to join.
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());

        OrganizationJpaEntity org = orgWithTerms();
        org.setClassStudentCap(20);
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
        // 19 STUDENT role + 1 STAFF = 20 total, but cap query counts only STUDENT
        when(membershipRepo.countByClassIdAndStatusAndRole(
                CLASS_ID, ClassMembershipJpaEntity.STATUS_ACTIVE, ClassMembershipJpaEntity.ROLE_STUDENT))
                .thenReturn(19L);
        when(avatarRepository.save(any(Avatar.class))).thenAnswer(inv -> inv.getArgument(0));

        // Should NOT throw
        service.enroll(cls, STUDENT_ID);
        verify(avatarRepository).save(any());
    }

    @Test
    void enrollStaff_provisionsBrandedAvatar_withStaffRole_andNoGroupJoin() {
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, "teacher-1")).thenReturn(Optional.empty());
        when(avatarRepository.save(any(Avatar.class))).thenAnswer(inv -> inv.getArgument(0));

        service.enrollStaff(cls, "teacher-1");

        ArgumentCaptor<ClassMembershipJpaEntity> captor =
                ArgumentCaptor.forClass(ClassMembershipJpaEntity.class);
        verify(membershipRepo).save(captor.capture());
        ClassMembershipJpaEntity saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(ClassMembershipJpaEntity.ROLE_STAFF);
        assertThat(saved.getStatus()).isEqualTo(ClassMembershipJpaEntity.STATUS_ACTIVE);
        assertThat(saved.getUserId()).isEqualTo("teacher-1");

        // STAFF preview must not put the teacher into student groups
        verify(classGroupService, never()).syncStudentJoin(any(), any());
    }

    @Test
    void enrollStaff_isIdempotent_returnsExistingAvatarWithoutReprovisioning() {
        OrgClassJpaEntity cls = classEntity();
        ClassMembershipJpaEntity existing = new ClassMembershipJpaEntity();
        existing.setId("m-staff");
        existing.setClassId(CLASS_ID);
        existing.setUserId("teacher-1");
        existing.setStudentAvatarId("staff-avatar-existing");
        existing.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, "teacher-1"))
                .thenReturn(Optional.of(existing));

        String avatarId = service.enrollStaff(cls, "teacher-1");

        assertThat(avatarId).isEqualTo("staff-avatar-existing");
        verify(avatarRepository, never()).save(any());
    }

    @Test
    void enrollStaff_doesNotCheckStudentCap_orOrgSubscriptionStatus() {
        // enrollStaff skips all cap and status checks — staff can always preview
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, "teacher-1")).thenReturn(Optional.empty());
        when(avatarRepository.save(any(Avatar.class))).thenAnswer(inv -> inv.getArgument(0));

        // Even if org is EXPIRED, enrollStaff should succeed (no call to orgRepo needed)
        service.enrollStaff(cls, "teacher-1");

        verify(orgRepo, never()).findById(any());
        verify(membershipRepo).save(any());
    }

    @Test
    void enroll_expiredOrg_throwsSubscriptionEndedError() {
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());

        OrganizationJpaEntity expiredOrg = new OrganizationJpaEntity();
        expiredOrg.setId("org-1");
        expiredOrg.setSubStatus(OrganizationJpaEntity.STATUS_EXPIRED);
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(expiredOrg));

        assertThatThrownBy(() -> service.enroll(cls, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("subscription has ended");

        verify(avatarRepository, never()).save(any());
    }

    @Test
    void enroll_canceledOrg_throwsSubscriptionEndedError() {
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());

        OrganizationJpaEntity canceledOrg = new OrganizationJpaEntity();
        canceledOrg.setId("org-1");
        canceledOrg.setSubStatus(OrganizationJpaEntity.STATUS_CANCELED);
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(canceledOrg));

        assertThatThrownBy(() -> service.enroll(cls, STUDENT_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("subscription has ended");

        verify(avatarRepository, never()).save(any());
    }
}
