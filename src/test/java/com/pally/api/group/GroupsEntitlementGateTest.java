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
import com.pally.infrastructure.persistence.group.StudyGroupJpaEntity;
import com.pally.infrastructure.persistence.group.StudyGroupJpaRepository;
import com.pally.infrastructure.persistence.knowledge.WikiPageJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.BeforeEach;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that groups create/join is gated by the Entitlements.groups()
 * flag: FREE users get UpgradeRequiredException("GROUPS"); PRO and above
 * are allowed through.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupsEntitlementGateTest {

    @Mock StudyGroupJpaRepository groupRepo;
    @Mock GroupMemberJpaRepository memberRepo;
    @Mock GroupSharedNoteJpaRepository sharedNoteRepo;
    @Mock GroupReportJpaRepository reportRepo;
    @Mock com.pally.infrastructure.persistence.group.GroupSystemPostJpaRepository systemPostRepo;
    @Mock WikiPageJpaRepository wikiPageRepo;
    @Mock RelevancePort relevancePort;
    @Mock UserJpaRepository userRepo;
    @Mock PremiumService premiumService;
    @Mock XpService xpService;

    @InjectMocks StudyGroupService service;

    private static final String USER = "user-1";

    // Minimal group entity returned by save
    private StudyGroupJpaEntity savedGroup;

    @BeforeEach
    void setUp() {
        savedGroup = new StudyGroupJpaEntity();
        savedGroup.setId("g1");
        savedGroup.setName("Test Group");
        savedGroup.setInviteCode("ABC123");
        savedGroup.setCreatedBy(USER);
        savedGroup.setCreatedAt(Instant.now());

        when(groupRepo.save(any())).thenReturn(savedGroup);
        when(groupRepo.existsByInviteCode(anyString())).thenReturn(false);
        when(memberRepo.save(any())).thenReturn(new GroupMemberJpaEntity());
        when(memberRepo.findByGroupId(anyString())).thenReturn(java.util.List.of());
    }

    // ── createGroup ───────────────────────────────────────────────────────────

    @Test
    void freeUser_createGroup_throwsUpgradeRequired_withGroupsFeatureCode() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.FREE);

        assertThatThrownBy(() -> service.createGroup(USER, Map.of("name", "Bio Study")))
                .isInstanceOf(UpgradeRequiredException.class)
                .hasFieldOrPropertyWithValue("feature", "GROUPS");
    }

    @Test
    void proUser_createGroup_isAllowed() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.PRO);

        assertThatNoException().isThrownBy(
                () -> service.createGroup(USER, Map.of("name", "Bio Study")));
    }

    @Test
    void maxUser_createGroup_isAllowed() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.MAX);

        assertThatNoException().isThrownBy(
                () -> service.createGroup(USER, Map.of("name", "Bio Study")));
    }

    @Test
    void familyUser_createGroup_isAllowed() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.FAMILY);

        assertThatNoException().isThrownBy(
                () -> service.createGroup(USER, Map.of("name", "Bio Study")));
    }

    // ── join ──────────────────────────────────────────────────────────────────

    @Test
    void freeUser_joinGroup_throwsUpgradeRequired_withGroupsFeatureCode() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.FREE);

        assertThatThrownBy(() -> service.join(USER, Map.of("inviteCode", "ABC123")))
                .isInstanceOf(UpgradeRequiredException.class)
                .hasFieldOrPropertyWithValue("feature", "GROUPS");
    }

    @Test
    void proUser_joinGroup_isAllowed() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.PRO);
        when(groupRepo.findByInviteCode("ABC123")).thenReturn(Optional.of(savedGroup));
        when(memberRepo.existsByGroupIdAndUserId(anyString(), anyString())).thenReturn(false);

        assertThatNoException().isThrownBy(
                () -> service.join(USER, Map.of("inviteCode", "ABC123")));
    }
}
