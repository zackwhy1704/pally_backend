package com.pally.domain.referral;

import com.pally.domain.progress.UserRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.referral.ReferralJpaEntity;
import com.pally.infrastructure.persistence.referral.ReferralJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {

    @Mock UserJpaRepository userRepo;
    @Mock ReferralJpaRepository referralRepo;
    @Mock UserRepository userDomainRepo;

    @InjectMocks ReferralService service;

    private static final String REFERRER = "referrer-1";
    private static final String REFEREE = "referee-1";

    private ReferralJpaEntity pendingReferral() {
        ReferralJpaEntity r = new ReferralJpaEntity();
        r.setReferrerUserId(REFERRER);
        r.setRefereeUserId(REFEREE);
        r.setStatus(ReferralJpaEntity.STATUS_PENDING);
        return r;
    }

    private void stubVerifiedReferee() {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(REFEREE);
        u.setEmailVerified(true);
        when(userRepo.findById(REFEREE)).thenReturn(Optional.of(u));
    }

    @Test
    void milestoneStarBonus_isPaidOnlyWhenCountLandsOnATier() {
        assertThat(ReferralService.milestoneStarBonus(2)).isZero();
        assertThat(ReferralService.milestoneStarBonus(3)).isEqualTo(100);
        assertThat(ReferralService.milestoneStarBonus(5)).isEqualTo(250);
        assertThat(ReferralService.milestoneStarBonus(10)).isEqualTo(600);
        assertThat(ReferralService.milestoneStarBonus(4)).isZero();
    }

    @Test
    void onFirstQuizAnswer_crossingTier_paysFlatRewardBothSides_plusReferrerMilestone() {
        when(referralRepo.findByRefereeUserId(REFEREE)).thenReturn(Optional.of(pendingReferral()));
        stubVerifiedReferee();
        when(referralRepo.countByReferrerUserIdAndStatus(REFERRER, ReferralJpaEntity.STATUS_ACTIVATED))
                .thenReturn(3L); // this activation is the 3rd → tier 1

        service.onFirstQuizAnswer(REFEREE);

        // Flat activation reward to both sides…
        verify(userDomainRepo).addXpAndStars(REFERRER, 50, 25);
        verify(userDomainRepo).addXpAndStars(REFEREE, 50, 25);
        // …plus the real 100★ milestone bonus to the referrer.
        verify(userDomainRepo).addXpAndStars(REFERRER, 0, 100);
    }

    @Test
    void onFirstQuizAnswer_notCrossingTier_paysNoMilestoneBonus() {
        when(referralRepo.findByRefereeUserId(REFEREE)).thenReturn(Optional.of(pendingReferral()));
        stubVerifiedReferee();
        when(referralRepo.countByReferrerUserIdAndStatus(REFERRER, ReferralJpaEntity.STATUS_ACTIVATED))
                .thenReturn(2L); // not a tier

        service.onFirstQuizAnswer(REFEREE);

        verify(userDomainRepo).addXpAndStars(REFERRER, 50, 25);
        verify(userDomainRepo).addXpAndStars(REFEREE, 50, 25);
        verify(userDomainRepo, never()).addXpAndStars(REFERRER, 0, 100);
    }

    @Test
    void onFirstQuizAnswer_unverifiedReferee_withholdsAllReward() {
        when(referralRepo.findByRefereeUserId(REFEREE)).thenReturn(Optional.of(pendingReferral()));
        UserJpaEntity unverified = new UserJpaEntity();
        unverified.setId(REFEREE);
        unverified.setEmailVerified(false);
        when(userRepo.findById(REFEREE)).thenReturn(Optional.of(unverified));

        service.onFirstQuizAnswer(REFEREE);

        verify(userDomainRepo, never()).addXpAndStars(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }
}
