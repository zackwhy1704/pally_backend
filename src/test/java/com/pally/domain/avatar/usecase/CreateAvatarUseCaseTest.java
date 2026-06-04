package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.consent.ConsentGuard;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.domain.subscription.PremiumService;
import com.pally.domain.subscription.SubscriptionTier;
import com.pally.shared.exception.UpgradeRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/// The L5-tutor-slot rule has direct freemium impact: a kid at L4 can't
/// make a second tutor; a kid at L5 can. This is the freemium "wall" we
/// turned into a goal. Locking it down with regression tests.
@ExtendWith(MockitoExtension.class)
class CreateAvatarUseCaseTest {

    @Mock AvatarRepository avatarRepo;
    @Mock PremiumService premiumService;
    @Mock UserRepository userRepository;
    @Mock ConsentGuard consentGuard; // no-op in these tests (ACTIVE accounts)

    @InjectMocks CreateAvatarUseCase useCase;

    private static final String USER = "u1";

    @BeforeEach
    void setup() {
        lenient().when(avatarRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PremiumService.Entitlement entitlement(boolean premium) {
        return new PremiumService.Entitlement(
                premium, "NONE", null, "free", null);
    }

    private void stubFreeTier() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.FREE);
    }

    private void stubProTier() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.PRO);
    }

    private void stubMaxTier() {
        when(premiumService.resolveTier(USER)).thenReturn(SubscriptionTier.MAX);
    }

    private UserStats stats(int level) {
        return new UserStats(USER, "kid", 0, level, 0, 0);
    }

    @Test
    void firstTutor_underL5_allowed() {
        stubFreeTier();
        when(avatarRepo.findByUserId(USER)).thenReturn(List.of());
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(2)));

        Avatar a = useCase.execute(USER, "Mochi", Subject.MATHS, CharacterType.MOCHI);
        assertThat(a).isNotNull();
    }

    @Test
    void secondTutor_underL5_blockedWithUpgradeRequired() {
        stubFreeTier();
        when(avatarRepo.findByUserId(USER)).thenReturn(List.of(existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(4)));

        assertThatThrownBy(() -> useCase.execute(
                USER, "Mochi2", Subject.MATHS, CharacterType.MOCHI))
                .isInstanceOf(UpgradeRequiredException.class)
                .satisfies(e ->
                        assertThat(((UpgradeRequiredException) e).getFeature())
                                .isEqualTo("CREATE_TUTOR"));
    }

    @Test
    void secondTutor_atL5_allowedWithoutPremium() {
        stubFreeTier();
        when(avatarRepo.findByUserId(USER)).thenReturn(List.of(existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(5)));

        Avatar a = useCase.execute(USER, "Bolt", Subject.SCIENCE, CharacterType.ZAP);
        assertThat(a).isNotNull();
    }

    @Test
    void thirdTutor_atL5_stillBlockedForFreeUser() {
        stubFreeTier();
        when(avatarRepo.findByUserId(USER))
                .thenReturn(List.of(existing(), existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(7)));

        assertThatThrownBy(() -> useCase.execute(
                USER, "Lumi", Subject.HISTORY, CharacterType.LUMIS))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    void proUser_canCreateUpToFiveTutors() {
        stubProTier();
        // 4 existing — adding a 5th should be allowed (4 < 5)
        when(avatarRepo.findByUserId(USER))
                .thenReturn(List.of(existing(), existing(), existing(), existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(3)));

        Avatar a = useCase.execute(USER, "Five", Subject.MATHS, CharacterType.MOCHI);
        assertThat(a).isNotNull();
    }

    @Test
    void proUser_cannotCreateSixthTutor() {
        stubProTier();
        // 5 existing — adding a 6th should be blocked (5 >= 5)
        when(avatarRepo.findByUserId(USER))
                .thenReturn(List.of(existing(), existing(), existing(), existing(), existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(3)));

        assertThatThrownBy(() -> useCase.execute(
                USER, "Six", Subject.SCIENCE, CharacterType.ZAP))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    void maxUser_ignoresCap_entirely() {
        stubMaxTier();
        // Even with many existing avatars, MAX tier is unlimited
        lenient().when(avatarRepo.findByUserId(USER))
                .thenReturn(List.of(existing(), existing(), existing(),
                        existing(), existing(), existing()));

        Avatar a = useCase.execute(USER, "X", Subject.MUSIC, CharacterType.PUDDI);
        assertThat(a).isNotNull();
    }

    /// Defensive: an unknown user shouldn't crash the avatar-create path
    /// — they get level 1 fallback, which means the standard 1-tutor cap.
    @Test
    void missingUser_fallsBackToLevelOne() {
        stubFreeTier();
        when(avatarRepo.findByUserId(USER)).thenReturn(List.of(existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute(
                USER, "y", Subject.ART, CharacterType.NORI))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    private static Avatar existing() {
        return Avatar.reconstitute(
                "a-existing", USER, "Existing", Subject.MATHS,
                CharacterType.MOCHI, 0, Instant.now());
    }
}
