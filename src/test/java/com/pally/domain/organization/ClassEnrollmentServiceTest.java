package com.pally.domain.organization;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.group.ClassGroupService;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClassEnrollmentServiceTest {

    @Mock AvatarRepository avatarRepository;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock ClassGroupService classGroupService;

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

    @Test
    void enroll_provisionsBrandedClosedBookAvatar_boundToCorpus_andJoinsGroup() {
        OrgClassJpaEntity cls = classEntity();
        when(membershipRepo.findByClassIdAndUserId(CLASS_ID, STUDENT_ID)).thenReturn(Optional.empty());
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
}
