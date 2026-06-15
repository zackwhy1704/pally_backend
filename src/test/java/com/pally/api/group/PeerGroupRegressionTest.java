package com.pally.api.group;

import com.pally.domain.group.StudyGroupService;
import com.pally.domain.knowledge.port.RelevancePort;
import com.pally.domain.progress.XpService;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.infrastructure.persistence.group.GroupMemberJpaEntity;
import com.pally.infrastructure.persistence.group.GroupMemberJpaRepository;
import com.pally.infrastructure.persistence.group.GroupReportJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSharedNoteJpaRepository;
import com.pally.infrastructure.persistence.group.GroupSystemPostJpaRepository;
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression: a PEER group still creates, joins, and leaves exactly as before
 * the CLASS-group feature landed. PEER groups default group_type=PEER and the
 * new guards never fire for them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PeerGroupRegressionTest {

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

    private static final String USER = "user-1";

    private StudyGroupJpaEntity peerGroup() {
        StudyGroupJpaEntity g = new StudyGroupJpaEntity();
        g.setId("g1");
        g.setName("Bio Buddies");
        g.setInviteCode("ABC123");
        g.setCreatedBy(USER);
        g.setCreatedAt(Instant.now());
        // group_type defaults to PEER, class_id null.
        return g;
    }

    @Test
    void peerGroup_create_defaultsToPeerType() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.PRO);
        when(groupRepo.existsByInviteCode(anyString())).thenReturn(false);
        when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepo.findByGroupId(anyString())).thenReturn(List.of());

        var resp = service.createGroup(USER, Map.of("name", "Bio Buddies"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp;
        assertThat(data).containsEntry("groupType", "PEER");
        // The owner is added with ROLE_OWNER (peer behaviour unchanged).
        verify(memberRepo).save(any(GroupMemberJpaEntity.class));
    }

    @Test
    void peerGroup_join_addsMember() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.PRO);
        when(groupRepo.findByInviteCode("ABC123")).thenReturn(Optional.of(peerGroup()));
        when(memberRepo.existsByGroupIdAndUserId(anyString(), anyString())).thenReturn(false);
        when(memberRepo.findByGroupId(anyString())).thenReturn(List.of());

        service.join(USER, Map.of("inviteCode", "ABC123"));
        verify(memberRepo).save(any(GroupMemberJpaEntity.class));
    }

    @Test
    void peerGroup_leave_deletesMembership_noGuard() {
        when(groupRepo.findById("g1")).thenReturn(Optional.of(peerGroup()));

        service.leave(USER, "g1");
        verify(memberRepo).deleteByGroupIdAndUserId("g1", USER);
    }
}
