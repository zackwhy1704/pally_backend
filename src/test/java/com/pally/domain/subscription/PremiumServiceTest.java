package com.pally.domain.subscription;

import com.pally.domain.account.AccountType;
import com.pally.domain.centre.OrgSubscriptionService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaEntity;
import com.pally.infrastructure.persistence.organization.ClassMembershipJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgClassJpaRepository;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaEntity;
import com.pally.infrastructure.persistence.organization.OrgStaffJpaRepository;
import com.pally.infrastructure.persistence.organization.OrganizationJpaEntity;
import com.pally.infrastructure.persistence.organization.OrganizationJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/// Locks the four entitlement branches the audit identified as the
/// "money path." A regression here can quietly hand premium to a free
/// user (or hide it from a paying one) — high blast radius.
@ExtendWith(MockitoExtension.class)
class PremiumServiceTest {

    @Mock UserRepository userRepo;
    @Mock SubscriptionJpaRepository subRepo;
    @Mock ClassMembershipJpaRepository membershipRepo;
    @Mock OrgClassJpaRepository classRepo;
    @Mock OrganizationJpaRepository orgRepo;
    @Mock OrgStaffJpaRepository staffRepo;
    @Mock OrgSubscriptionService orgSubService;

    @InjectMocks PremiumService premiumService;

    private User solo(String id) {
        User u = new User();
        u.setId(id);
        u.setAccountType(AccountType.SOLO);
        return u;
    }

    private User child(String id, String parentId) {
        User u = new User();
        u.setId(id);
        u.setAccountType(AccountType.CHILD);
        u.setParentId(parentId);
        return u;
    }

    private SubscriptionJpaEntity activeSub(String userId, String plan) {
        SubscriptionJpaEntity s = new SubscriptionJpaEntity();
        s.setUserId(userId);
        s.setStatus("active");
        s.setPlan(plan);
        s.setCurrentPeriodEnd(Instant.now().plusSeconds(86_400));
        return s;
    }

    @Test
    void resolve_userWithActiveOwnSubscription_isPremiumViaSelf() {
        when(userRepo.findById("u1")).thenReturn(Optional.of(solo("u1")));
        when(subRepo.findById("u1")).thenReturn(Optional.of(activeSub("u1", "individual_monthly")));

        var e = premiumService.resolve("u1");

        assertThat(e.isPremium()).isTrue();
        assertThat(e.source()).isEqualTo("SELF");
        assertThat(e.plan()).isEqualTo("individual_monthly");
    }

    @Test
    void resolve_childInheritsActivePremiumFromParent() {
        when(userRepo.findById("kid")).thenReturn(Optional.of(child("kid", "parent")));
        when(subRepo.findById("kid")).thenReturn(Optional.empty());
        when(subRepo.findById("parent"))
                .thenReturn(Optional.of(activeSub("parent", "family_monthly")));

        var e = premiumService.resolve("kid");

        assertThat(e.isPremium()).isTrue();
        assertThat(e.source()).isEqualTo("PARENT");
        assertThat(e.plan()).isEqualTo("family_monthly");
    }

    @Test
    void resolve_childWithNoActiveParentSub_isFree() {
        when(userRepo.findById("kid")).thenReturn(Optional.of(child("kid", "parent")));
        when(subRepo.findById("kid")).thenReturn(Optional.empty());
        when(subRepo.findById("parent")).thenReturn(Optional.empty());

        var e = premiumService.resolve("kid");

        assertThat(e.isPremium()).isFalse();
        assertThat(e.source()).isEqualTo("NONE");
    }

    @Test
    void resolve_userWithNoSubscription_isFree() {
        when(userRepo.findById("u2")).thenReturn(Optional.of(solo("u2")));
        when(subRepo.findById("u2")).thenReturn(Optional.empty());

        var e = premiumService.resolve("u2");

        assertThat(e.isPremium()).isFalse();
        assertThat(e.source()).isEqualTo("NONE");
        assertThat(e.status()).isEqualTo("free");
    }

    @Test
    void resolve_userWithCancelledSubscription_isFree() {
        when(userRepo.findById("u3")).thenReturn(Optional.of(solo("u3")));
        SubscriptionJpaEntity cancelled = activeSub("u3", "family_monthly");
        cancelled.setStatus("canceled");
        when(subRepo.findById("u3")).thenReturn(Optional.of(cancelled));

        var e = premiumService.resolve("u3");

        assertThat(e.isPremium()).isFalse();
        // Plan is NOT echoed back so the UI doesn't have to special-case
        // "premium=false, plan=family_monthly" — the audit's contract.
        assertThat(e.plan()).isNull();
    }

    @Test
    void resolve_unknownUser_isFree() {
        when(userRepo.findById("ghost")).thenReturn(Optional.empty());

        var e = premiumService.resolve("ghost");

        assertThat(e.isPremium()).isFalse();
        assertThat(e.source()).isEqualTo("NONE");
    }

    @Test
    void resolveTier_trialUser_returnsMAX() {
        User u = solo("trial_u");
        u.setTrialStatus(PremiumService.TRIAL_ACTIVE);
        u.setTrialStartedAt(Instant.now().minusSeconds(3600));
        u.setTrialEndsAt(Instant.now().plusSeconds(86_400 * 6));
        when(userRepo.findById("trial_u")).thenReturn(Optional.of(u));
        when(subRepo.findById("trial_u")).thenReturn(Optional.empty());

        SubscriptionTier tier = premiumService.resolveTier("trial_u");

        assertThat(tier).isEqualTo(SubscriptionTier.MAX);
    }

    @Test
    void resolve_userWithTrialingSubscription_isPremium() {
        when(userRepo.findById("u4")).thenReturn(Optional.of(solo("u4")));
        SubscriptionJpaEntity trial = activeSub("u4", "family_monthly");
        trial.setStatus("trialing");
        when(subRepo.findById("u4")).thenReturn(Optional.of(trial));

        var e = premiumService.resolve("u4");

        assertThat(e.isPremium()).isTrue();
        assertThat(e.source()).isEqualTo("SELF");
        // trialEndsAt populated when status=trialing so the UI can show
        // the 7-day countdown.
        assertThat(e.trialEndsAt()).isNotNull();
    }

    @Test
    void resolveTier_centreSourced_returnsPRO_andIsCentreSourcedTrue() {
        // Arrange: user with no own subscription and no parent, but an active centre enrolment
        User u = solo("centre-student");
        when(userRepo.findById("centre-student")).thenReturn(Optional.of(u));
        when(subRepo.findById("centre-student")).thenReturn(Optional.empty());

        ClassMembershipJpaEntity m = new ClassMembershipJpaEntity();
        m.setId("mem-1");
        m.setUserId("centre-student");
        m.setClassId("class-1");
        m.setStatus(ClassMembershipJpaEntity.STATUS_ACTIVE);
        when(membershipRepo.findByUserId("centre-student")).thenReturn(List.of(m));

        com.pally.infrastructure.persistence.organization.OrgClassJpaEntity cls =
                new com.pally.infrastructure.persistence.organization.OrgClassJpaEntity();
        cls.setId("class-1");
        cls.setOrganizationId("org-1");
        when(classRepo.findById("class-1")).thenReturn(Optional.of(cls));

        com.pally.infrastructure.persistence.organization.OrganizationJpaEntity org =
                new com.pally.infrastructure.persistence.organization.OrganizationJpaEntity();
        org.setId("org-1");
        org.setTier("CENTRE");
        org.setSubStatus(com.pally.infrastructure.persistence.organization.OrganizationJpaEntity.STATUS_ACTIVE);
        when(orgRepo.findById("org-1")).thenReturn(Optional.of(org));
        when(orgSubService.isEntitled(org)).thenReturn(true);

        // Act
        PremiumService.TierContext ctx = premiumService.resolveTierContext("centre-student");

        // Assert: CENTRE source maps to PRO tier, and the centre-sourced flag is set
        assertThat(ctx.tier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(ctx.isCentreSourced()).isTrue();
    }

    // ── Phase 2: ADMIN and staff/owner branches ───────────────────────────────

    @Test
    void resolve_adminUser_returnsMaxTier_notCentreSourced() {
        User u = solo("admin-1");
        u.setRole("ADMIN");
        when(userRepo.findById("admin-1")).thenReturn(Optional.of(u));

        PremiumService.Entitlement ent = premiumService.resolve("admin-1");

        assertThat(ent.isPremium()).isTrue();
        assertThat(ent.source()).isEqualTo("ADMIN");
        assertThat(ent.plan()).isEqualTo("max");

        PremiumService.TierContext ctx = premiumService.resolveTierContext("admin-1");
        assertThat(ctx.tier()).isEqualTo(SubscriptionTier.MAX);
        assertThat(ctx.isCentreSourced()).isFalse();
    }

    @Test
    void resolve_orgOwnerWithEntitledOrg_returnsProCentreSourced() {
        User u = solo("owner-1");
        u.setRole("USER");
        when(userRepo.findById("owner-1")).thenReturn(Optional.of(u));
        when(subRepo.findById("owner-1")).thenReturn(Optional.empty());

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId("org-1");
        org.setOwnerUserId("owner-1");
        org.setSubStatus(OrganizationJpaEntity.STATUS_ACTIVE);
        when(orgRepo.findByOwnerUserId("owner-1")).thenReturn(List.of(org));
        when(orgSubService.isEntitled(org)).thenReturn(true);

        PremiumService.TierContext ctx = premiumService.resolveTierContext("owner-1");

        assertThat(ctx.tier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(ctx.isCentreSourced()).isTrue();
    }

    @Test
    void resolve_activeStaffMemberWithEntitledOrg_returnsProCentreSourced() {
        User u = solo("teacher-1");
        u.setRole("USER");
        when(userRepo.findById("teacher-1")).thenReturn(Optional.of(u));
        when(subRepo.findById("teacher-1")).thenReturn(Optional.empty());
        when(orgRepo.findByOwnerUserId("teacher-1")).thenReturn(List.of()); // not an owner

        OrgStaffJpaEntity staffRow = new OrgStaffJpaEntity();
        staffRow.setUserId("teacher-1");
        staffRow.setOrgId("org-2");
        staffRow.setStatus(OrgStaffJpaEntity.STATUS_ACTIVE);
        when(staffRepo.findFirstByUserIdAndStatus("teacher-1", OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(Optional.of(staffRow));

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId("org-2");
        org.setSubStatus(OrganizationJpaEntity.STATUS_PILOT);
        org.setPilotEndsAt(Instant.now().plusSeconds(86_400 * 30));
        when(orgRepo.findById("org-2")).thenReturn(Optional.of(org));
        when(orgSubService.isEntitled(org)).thenReturn(true);

        PremiumService.TierContext ctx = premiumService.resolveTierContext("teacher-1");

        assertThat(ctx.tier()).isEqualTo(SubscriptionTier.PRO);
        assertThat(ctx.isCentreSourced()).isTrue();
    }

    @Test
    void resolve_staffOfLapsedOrg_isNotGrantedPro() {
        User u = solo("teacher-2");
        u.setRole("USER");
        when(userRepo.findById("teacher-2")).thenReturn(Optional.of(u));
        when(subRepo.findById("teacher-2")).thenReturn(Optional.empty());
        when(orgRepo.findByOwnerUserId("teacher-2")).thenReturn(List.of());

        OrgStaffJpaEntity staffRow = new OrgStaffJpaEntity();
        staffRow.setUserId("teacher-2");
        staffRow.setOrgId("org-expired");
        staffRow.setStatus(OrgStaffJpaEntity.STATUS_ACTIVE);
        when(staffRepo.findFirstByUserIdAndStatus("teacher-2", OrgStaffJpaEntity.STATUS_ACTIVE))
                .thenReturn(Optional.of(staffRow));

        OrganizationJpaEntity org = new OrganizationJpaEntity();
        org.setId("org-expired");
        org.setSubStatus(OrganizationJpaEntity.STATUS_EXPIRED);
        when(orgRepo.findById("org-expired")).thenReturn(Optional.of(org));
        when(orgSubService.isEntitled(org)).thenReturn(false);

        PremiumService.Entitlement ent = premiumService.resolve("teacher-2");

        assertThat(ent.source()).isNotEqualTo("CENTRE");
        assertThat(ent.isPremium()).isFalse();
    }

    @Test
    void adminDoesNotGetBlockedBySelfSubCheck_MaxTierWins() {
        // ADMIN branch fires before subRepo is ever consulted — no subscription stub needed.
        User u = solo("admin-2");
        u.setRole("ADMIN");
        when(userRepo.findById("admin-2")).thenReturn(Optional.of(u));

        PremiumService.Entitlement ent = premiumService.resolve("admin-2");
        assertThat(ent.source()).isEqualTo("ADMIN");
        assertThat(ent.plan()).isEqualTo("max");
    }
}
