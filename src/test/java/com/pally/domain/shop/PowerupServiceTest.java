package com.pally.domain.shop;

import com.pally.infrastructure.persistence.powerup.UserPowerupJpaEntity;
import com.pally.infrastructure.persistence.powerup.UserPowerupJpaRepository;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Locks the atomic-spend + atomic-consume invariants. Same pattern as
/// CharacterShopService: 0-row UPDATE → BusinessException, never a
/// silent failure. Catalog prices double as constants the tests pin.
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PowerupServiceTest {

    @Mock UserJpaRepository userRepo;
    @Mock UserPowerupJpaRepository powerupRepo;
    @InjectMocks PowerupService service;

    private static final String USER = "u1";

    private UserJpaEntity userWithStars(int stars) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(USER);
        u.setStars(stars);
        return u;
    }

    @Test
    void type_parse_unknown_throws() {
        assertThatThrownBy(() -> PowerupService.Type.parse("BAZINGA"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown powerup type");
        assertThatThrownBy(() -> PowerupService.Type.parse(null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void type_parse_isCaseInsensitive() {
        assertThat(PowerupService.Type.parse("hint_token"))
                .isEqualTo(PowerupService.Type.HINT_TOKEN);
    }

    @Test
    void buy_hint_atomicSpend_upserts_returnsBalance() {
        when(userRepo.spendStars(USER, 50)).thenReturn(1);
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(userWithStars(450)));
        when(powerupRepo.findById(any()))
                .thenReturn(Optional.of(new UserPowerupJpaEntity(USER, "HINT_TOKEN", 3)));

        var result = service.buy(USER, PowerupService.Type.HINT_TOKEN);

        assertThat(result.get("type")).isEqualTo("HINT_TOKEN");
        assertThat(result.get("count")).isEqualTo(3);
        assertThat(result.get("newStarBalance")).isEqualTo(450);
        verify(powerupRepo).upsertCount(USER, "HINT_TOKEN", 1);
    }

    @Test
    void buy_insufficient_throwsNotEnough_doesNotUpsert() {
        when(userRepo.spendStars(USER, 75)).thenReturn(0);

        assertThatThrownBy(() -> service.buy(USER, PowerupService.Type.DOUBLE_XP))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Not enough stars (need 75)");
        verify(powerupRepo, never()).upsertCount(any(), any(), any(int.class));
    }

    @Test
    void consume_happyPath_decrementsAndReturnsRemaining() {
        when(powerupRepo.consume(USER, "BONUS_QUIZ")).thenReturn(1);
        when(powerupRepo.findById(any()))
                .thenReturn(Optional.of(new UserPowerupJpaEntity(USER, "BONUS_QUIZ", 1)));

        var result = service.consume(USER, PowerupService.Type.BONUS_QUIZ);

        assertThat(result.get("count")).isEqualTo(1);
    }

    @Test
    void consume_noTokens_throwsKidFriendlyMessage() {
        when(powerupRepo.consume(USER, "HINT_TOKEN")).thenReturn(0);

        assertThatThrownBy(() -> service.consume(USER, PowerupService.Type.HINT_TOKEN))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no hint tokens left");
    }

    @Test
    void inventory_emptyForNewUser_zeroForEachType() {
        when(powerupRepo.findById_UserId(USER)).thenReturn(List.of());

        Map<String, Integer> inv = service.inventory(USER);

        assertThat(inv).hasSize(3);
        assertThat(inv).containsEntry("HINT_TOKEN", 0);
        assertThat(inv).containsEntry("DOUBLE_XP", 0);
        assertThat(inv).containsEntry("BONUS_QUIZ", 0);
    }

    @Test
    void inventory_reflectsRealCounts() {
        when(powerupRepo.findById_UserId(USER)).thenReturn(List.of(
                new UserPowerupJpaEntity(USER, "HINT_TOKEN", 5),
                new UserPowerupJpaEntity(USER, "DOUBLE_XP", 2)));

        var inv = service.inventory(USER);

        assertThat(inv).containsEntry("HINT_TOKEN", 5);
        assertThat(inv).containsEntry("DOUBLE_XP", 2);
        assertThat(inv).containsEntry("BONUS_QUIZ", 0);
    }

    @Test
    void catalog_includesAllThreeTypes_withCosts() {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> powerups =
                (Map<String, Map<String, Object>>) service.catalog().get("powerups");
        assertThat(powerups).containsKeys("HINT_TOKEN", "DOUBLE_XP", "BONUS_QUIZ");
        assertThat(powerups.get("HINT_TOKEN").get("cost")).isEqualTo(50);
        assertThat(powerups.get("DOUBLE_XP").get("cost")).isEqualTo(75);
        assertThat(powerups.get("BONUS_QUIZ").get("cost")).isEqualTo(100);
    }
}
