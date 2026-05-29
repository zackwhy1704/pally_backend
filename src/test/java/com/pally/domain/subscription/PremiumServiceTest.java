package com.pally.domain.subscription;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/// Locks the four entitlement branches the audit identified as the
/// "money path." A regression here can quietly hand premium to a free
/// user (or hide it from a paying one) — high blast radius.
@ExtendWith(MockitoExtension.class)
class PremiumServiceTest {

    @Mock UserJpaRepository userRepo;
    @Mock SubscriptionJpaRepository subRepo;

    @InjectMocks PremiumService premiumService;

    private UserJpaEntity solo(String id) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        u.setAccountType("SOLO");
        return u;
    }

    private UserJpaEntity child(String id, String parentId) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(id);
        u.setAccountType("CHILD");
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
}
