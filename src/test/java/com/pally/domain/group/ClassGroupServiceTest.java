package com.pally.domain.group;

import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassGroupServiceTest {

    @Mock StudyGroupJpaRepository groupRepo;
    @Mock GroupMemberJpaRepository memberRepo;
    @Mock GroupSystemPostRepository systemPostRepo;
    @Mock OrgClassJpaRepository classRepo;
    @Mock OrganizationJpaRepository orgRepo;

    @InjectMocks ClassGroupService service;

    private OrgClassJpaEntity cls() {
        OrgClassJpaEntity c = new OrgClassJpaEntity();
        c.setId("class-1");
        c.setOrganizationId("org-1");
        c.setName("P4 Math");
        c.setSubject("MATHS");
        return c;
    }

    @Test
    void ensureClassGroup_createsClassGroup_withOwnerAsTeacher() {
        when(groupRepo.findByClassId("class-1")).thenReturn(Optional.empty());
        when(groupRepo.existsByInviteCode(anyString())).thenReturn(false);
        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId("org-1");
        org.setOwnerUserId("owner-1");
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
        when(memberRepo.findById(any())).thenReturn(Optional.empty());

        StudyGroupJpaEntity created = service.ensureClassGroup(cls());

        ArgumentCaptor<StudyGroupJpaEntity> g = ArgumentCaptor.forClass(StudyGroupJpaEntity.class);
        verify(groupRepo).save(g.capture());
        assertThat(g.getValue().getGroupType()).isEqualTo(StudyGroupJpaEntity.TYPE_CLASS);
        assertThat(g.getValue().getClassId()).isEqualTo("class-1");
        assertThat(g.getValue().getCreatedBy()).isEqualTo("owner-1");

        ArgumentCaptor<GroupMemberJpaEntity> m = ArgumentCaptor.forClass(GroupMemberJpaEntity.class);
        verify(memberRepo).save(m.capture());
        assertThat(m.getValue().getRole()).isEqualTo(GroupMemberJpaEntity.ROLE_TEACHER);
        assertThat(m.getValue().getUserId()).isEqualTo("owner-1");
    }

    @Test
    void ensureClassGroup_isIdempotent_whenGroupExists() {
        StudyGroupJpaEntity existing = new StudyGroupJpaEntity();
        existing.setId("g1");
        existing.setClassId("class-1");
        when(groupRepo.findByClassId("class-1")).thenReturn(Optional.of(existing));

        StudyGroupJpaEntity result = service.ensureClassGroup(cls());

        assertThat(result).isSameAs(existing);
        verify(groupRepo, never()).save(any());
    }

    @Test
    void syncStudentJoin_addsMemberAsRoleMember() {
        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId("g1");
        g.setClassId("class-1");
        when(groupRepo.findByClassId("class-1")).thenReturn(Optional.of(g));
        when(memberRepo.existsByGroupIdAndUserId("g1", "student-1")).thenReturn(false);
        when(memberRepo.findById(any())).thenReturn(Optional.empty());

        service.syncStudentJoin(cls(), "student-1");

        ArgumentCaptor<GroupMemberJpaEntity> m = ArgumentCaptor.forClass(GroupMemberJpaEntity.class);
        verify(memberRepo).save(m.capture());
        assertThat(m.getValue().getRole()).isEqualTo(GroupMemberJpaEntity.ROLE_MEMBER);
    }

    @Test
    void syncStudentLeave_removesMembership() {
        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId("g1");
        when(groupRepo.findByClassId("class-1")).thenReturn(Optional.of(g));

        service.syncStudentLeave("class-1", "student-1");
        verify(memberRepo).deleteByGroupIdAndUserId("g1", "student-1");
    }

    @Test
    void postSystemMessage_persistsPost_whenGroupExists() {
        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId("g1");
        when(groupRepo.findByClassId("class-1")).thenReturn(Optional.of(g));
        when(systemPostRepo.save(anyString(), anyString(), anyString(), any()))
                .thenReturn("post-id-1");

        String id = service.postSystemMessage("class-1",
                ClassGroupService.KIND_ANSWERS_RELEASED, "Answers are out", "assign-1");

        assertThat(id).isEqualTo("post-id-1");
        verify(systemPostRepo).save("g1", ClassGroupService.KIND_ANSWERS_RELEASED,
                "Answers are out", "assign-1");
    }

    @Test
    void postSystemMessage_noOp_whenNoGroup() {
        when(groupRepo.findByClassId("class-1")).thenReturn(Optional.empty());

        String id = service.postSystemMessage("class-1",
                ClassGroupService.KIND_MUDDIEST, "x", null);

        assertThat(id).isNull();
        verify(systemPostRepo, never()).save(anyString(), anyString(), anyString(), any());
    }
}
