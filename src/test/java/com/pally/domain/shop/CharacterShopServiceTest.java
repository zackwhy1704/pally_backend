package com.pally.domain.shop;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.shop.CharacterUnlockJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Tests the audit's D1 fix for the shop path. Specifically:
///  - buyStreakFreeze relies on the atomic UPDATE result, not a stale
///    pre-image read, to decide success.
///  - openMysteryBox uses spendStars (atomic) and never read-modify-writes
///    the stars field.
@ExtendWith(MockitoExtension.class)
class CharacterShopServiceTest {

    @Mock UserJpaRepository userRepo;
    @Mock CharacterUnlockJpaRepository unlockRepo;
    @InjectMocks CharacterShopService shop;

    private static final String USER = "u1";

    private UserJpaEntity user(int stars, int freezes, int level) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(USER);
        u.setStars(stars);
        u.setStreakFreezes(freezes);
        u.setLevel(level);
        return u;
    }

    @Test
    void buyStreakFreeze_happyPath_returnsNewBalance() {
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(200, 0, 1)))   // pre-flight
                .thenReturn(Optional.of(user(50, 1, 1)));   // post-update
        when(userRepo.buyStreakFreeze(USER, 150, 3)).thenReturn(1);

        var result = shop.buyStreakFreeze(USER);

        assertThat(result.get("freezes")).isEqualTo(1);
        assertThat(result.get("freezeCap")).isEqualTo(3);
        assertThat(result.get("newStarBalance")).isEqualTo(50);
    }

    @Test
    void buyStreakFreeze_atL20_capIsFive() {
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(200, 3, 20)))  // pre-flight
                .thenReturn(Optional.of(user(50, 4, 20)));  // post-update
        when(userRepo.buyStreakFreeze(USER, 150, 5)).thenReturn(1);

        var result = shop.buyStreakFreeze(USER);

        assertThat(result.get("freezeCap")).isEqualTo(5);
        verify(userRepo).buyStreakFreeze(USER, 150, 5);
    }

    @Test
    void buyStreakFreeze_atCap_rejectsWithoutCallingUpdate() {
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(500, 3, 1)));

        assertThatThrownBy(() -> shop.buyStreakFreeze(USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Freezes are full");
        verify(userRepo, never())
                .buyStreakFreeze(anyString(), anyInt(), anyInt());
    }

    /// Race-loss: the pre-flight check passed, but the atomic UPDATE
    /// affected 0 rows because someone else won the race. The post-image
    /// stars >= 150 → other thread bought a freeze, so we should report
    /// "freezes full" (the more useful signal).
    @Test
    void buyStreakFreeze_raceLoss_freezesFull() {
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(200, 2, 1)))   // pre-flight read
                .thenReturn(Optional.of(user(200, 3, 1)));  // post-race read
        when(userRepo.buyStreakFreeze(USER, 150, 3)).thenReturn(0);

        assertThatThrownBy(() -> shop.buyStreakFreeze(USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Freezes are full");
    }

    /// Race-loss where the fresh row shows insufficient stars — caller
    /// should see "Not enough stars" so the UX can point at "buy more".
    @Test
    void buyStreakFreeze_raceLoss_lowStars_reportsNotEnough() {
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(200, 2, 1)))   // pre-flight
                .thenReturn(Optional.of(user(50, 2, 1)));   // post-race
        when(userRepo.buyStreakFreeze(USER, 150, 3)).thenReturn(0);

        assertThatThrownBy(() -> shop.buyStreakFreeze(USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not enough stars");
    }

    @Test
    void openMysteryBox_atomicSpend_happyPath() {
        when(unlockRepo.existsByUserIdAndCharacter(eq(USER), anyString()))
                .thenReturn(false);
        when(userRepo.spendStars(USER, 600)).thenReturn(1);
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(400, 0, 1)));

        var result = shop.openMysteryBox(USER);

        assertThat(result.get("newStarBalance")).isEqualTo(400);
        assertThat(result.get("isNew")).isEqualTo(true);
        verify(userRepo, times(1)).spendStars(USER, 600);
        verify(unlockRepo, times(1)).save(any());
    }

    @Test
    void openMysteryBox_insufficient_throwsBeforeUnlock() {
        when(unlockRepo.existsByUserIdAndCharacter(eq(USER), anyString()))
                .thenReturn(false);
        when(userRepo.spendStars(USER, 600)).thenReturn(0);

        assertThatThrownBy(() -> shop.openMysteryBox(USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not enough stars");
        verify(unlockRepo, never()).save(any());
    }

    @Test
    void openMysteryBox_duplicate_chargesRefundPriceOnly() {
        when(unlockRepo.existsByUserIdAndCharacter(eq(USER), anyString()))
                .thenReturn(true);
        when(userRepo.spendStars(USER, 300)).thenReturn(1);
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(700, 0, 1)));

        var result = shop.openMysteryBox(USER);

        assertThat(result.get("isNew")).isEqualTo(false);
        assertThat(result.get("newStarBalance")).isEqualTo(700);
        verify(userRepo, times(1)).spendStars(USER, 300);
        verify(userRepo, never()).spendStars(USER, 600);
        // No unlock insert when we already own the character.
        verify(unlockRepo, never()).save(any());
    }
}
