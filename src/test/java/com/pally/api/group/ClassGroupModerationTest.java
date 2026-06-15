package com.pally.api.group;

import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.progress.XpService;
import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.GroupReportJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CLASS-group moderation invariants: students cannot self-join, leave, or kick
 * in a CLASS group (those return 403), while a TEACHER may. These guards are
 * what keep enrolment-synced membership authoritative.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClassGroupModerationTest {

    @Mock StudyGroupJpaRepository groupRepo;
    @Mock GroupMemberJpaRepository memberRepo;
    @Mock GroupSharedNoteJpaRepository sharedNoteRepo;
    @Mock GroupReportJpaRepository reportRepo;
    @Mock GroupSystemPostJpaRepository systemPostRepo;
    @Mock WikiPageJpaRepository wikiPageRepo;
    @Mock RelevancePort relevancePort;
    @Mock UserJpaRepository userRepo;
    @Mock PremiumService premiumService;
    @Mock XpService xpService;

    @InjectMocks StudyGroupService service;

    private static final String STUDENT = "student-1";
    private static final String TEACHER = "teacher-1";
    private static final String GID = "class-group-1";

    private StudyGroupJpaEntity classGroup() {
        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId(GID);
        g.setName("P4 Math");
        g.setInviteCode("CLS123");
        g.setGroupType(StudyGroupJpaEntity.TYPE_CLASS);
        g.setClassId("class-1");
        g.setCreatedBy(TEACHER);
        g.setCreatedAt(Instant.now());
        return g;
    }

    private GroupMemberJpaEntity member(String role) {
        GroupMemberJpaEntity m = new GroupMemberJpaEntity();
        m.setGroupId(GID);
        m.setRole(role);
        m.setJoinedAt(Instant.now());
        return m;
    }

    @Test
    void student_cannotSelfJoinClassGroupByCode_returns403() {
        when(premiumService.resolveTier(STUDENT))
                .thenReturn(com.pally.domain.subscription.SubscriptionTier.CENTRE);
        when(groupRepo.findByInviteCode("CLS123")).thenReturn(Optional.of(classGroup()));

        assertThatThrownBy(() -> service.join(STUDENT, Map.of("inviteCode", "CLS123")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
        verify(memberRepo, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void student_cannotLeaveClassGroup_returns403() {
        when(groupRepo.findById(GID)).thenReturn(Optional.of(classGroup()));
        // student is a plain MEMBER → isTeacher == false
        when(memberRepo.findById(new GroupMemberJpaEntity.PK(GID, STUDENT)))
                .thenReturn(Optional.of(member(GroupMemberJpaEntity.ROLE_MEMBER)));

        assertThatThrownBy(() -> service.leave(STUDENT, GID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
        verify(memberRepo, never()).deleteByGroupIdAndUserId(anyString(), anyString());
    }

    @Test
    void teacher_canLeaveClassGroup() {
        when(groupRepo.findById(GID)).thenReturn(Optional.of(classGroup()));
        when(memberRepo.findById(new GroupMemberJpaEntity.PK(GID, TEACHER)))
                .thenReturn(Optional.of(member(GroupMemberJpaEntity.ROLE_TEACHER)));

        service.leave(TEACHER, GID);
        verify(memberRepo).deleteByGroupIdAndUserId(GID, TEACHER);
    }

    @Test
    void student_cannotKickInClassGroup_returns403() {
        // kick uses ensureModerator → requires OWNER or TEACHER role
        when(memberRepo.findById(new GroupMemberJpaEntity.PK(GID, STUDENT)))
                .thenReturn(Optional.of(member(GroupMemberJpaEntity.ROLE_MEMBER)));

        assertThatThrownBy(() -> service.kickMember(STUDENT, GID, "victim"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("httpStatus", 403);
    }

    @Test
    void summary_exposesGroupTypeAndClassId() {
        when(memberRepo.findByGroupId(GID)).thenReturn(java.util.List.of());
        // listMyGroups maps via toSummary
        when(groupRepo.findGroupsForUser(TEACHER)).thenReturn(java.util.List.of(classGroup()));

        var resp = service.listMyGroups(TEACHER);
        @SuppressWarnings("unchecked")
        var groups = (java.util.List<Map<String, Object>>) resp.get("groups");
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0)).containsEntry("groupType", "CLASS");
        assertThat(groups.get(0)).containsEntry("classId", "class-1");
    }
}
