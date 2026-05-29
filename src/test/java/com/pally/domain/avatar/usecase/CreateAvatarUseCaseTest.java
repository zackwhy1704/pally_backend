package com.pally.domain.avatar.usecase;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.avatar.CharacterType;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.SeedContentService;
import com.pally.domain.progress.UserRepository;
import com.pally.domain.progress.UserStats;
import com.pally.domain.subscription.PremiumService;
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
    @Mock SeedContentService seedContentService;
    @Mock PremiumService premiumService;
    @Mock UserRepository userRepository;

    @InjectMocks CreateAvatarUseCase useCase;

    private static final String USER = "u1";

    @BeforeEach
    void setup() {
        lenient().when(seedContentService.seedForAvatar(any(), any())).thenReturn(0);
        lenient().when(avatarRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private PremiumService.Entitlement entitlement(boolean premium) {
        return new PremiumService.Entitlement(
                premium, "NONE", null, "free", null);
    }

    private UserStats stats(int level) {
        return new UserStats(USER, "kid", 0, level, 0, 0);
    }

    @Test
    void firstTutor_underL5_allowed() {
        when(premiumService.resolve(USER)).thenReturn(entitlement(false));
        when(avatarRepo.findByUserId(USER)).thenReturn(List.of());
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(2)));

        Avatar a = useCase.execute(USER, "Mochi", Subject.MATHS, CharacterType.MOCHI);
        assertThat(a).isNotNull();
    }

    @Test
    void secondTutor_underL5_blockedWithUpgradeRequired() {
        when(premiumService.resolve(USER)).thenReturn(entitlement(false));
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
        when(premiumService.resolve(USER)).thenReturn(entitlement(false));
        when(avatarRepo.findByUserId(USER)).thenReturn(List.of(existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(5)));

        Avatar a = useCase.execute(USER, "Bolt", Subject.SCIENCE, CharacterType.ZAP);
        assertThat(a).isNotNull();
    }

    @Test
    void thirdTutor_atL5_stillBlocked() {
        when(premiumService.resolve(USER)).thenReturn(entitlement(false));
        when(avatarRepo.findByUserId(USER))
                .thenReturn(List.of(existing(), existing()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(stats(7)));

        assertThatThrownBy(() -> useCase.execute(
                USER, "Lumi", Subject.HISTORY, CharacterType.LUMIS))
                .isInstanceOf(UpgradeRequiredException.class);
    }

    @Test
    void premiumUser_ignoresCap_entirely() {
        when(premiumService.resolve(USER)).thenReturn(entitlement(true));
        // findByUserId is not consulted on the premium branch, but the
        // domain doesn't promise that — lenient setup keeps the test
        // robust to future refactors.
        lenient().when(avatarRepo.findByUserId(USER))
                .thenReturn(List.of(existing(), existing(), existing()));

        Avatar a = useCase.execute(USER, "X", Subject.MUSIC, CharacterType.PUDDI);
        assertThat(a).isNotNull();
    }

    /// Defensive: an unknown user shouldn't crash the avatar-create path
    /// — they get level 1 fallback, which means the standard 1-tutor cap.
    @Test
    void missingUser_fallsBackToLevelOne() {
        when(premiumService.resolve(USER)).thenReturn(entitlement(false));
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
