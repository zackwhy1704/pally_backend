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
    @Mock com.pally.infrastructure.persistence.mochi.MochiCharacterJpaRepository catalogRepo;
    @Mock com.pally.infrastructure.persistence.mochi.UserMochiJpaRepository userMochiRepo;
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

    /// Build a catalog row matching the seeded Core theme so mystery-box
    /// tests behave as before the V40 refactor.
    private com.pally.infrastructure.persistence.mochi.MochiCharacterJpaEntity
            catalogRow(String id, String rarity) {
        var c = new com.pally.infrastructure.persistence.mochi.MochiCharacterJpaEntity();
        c.setId(id);
        c.setName(id);
        c.setThemeId("THEME_CORE");
        c.setRarity(rarity);
        c.setAcquisition("MYSTERY_BOX");
        c.setStarCost(null);
        c.setActiveFrom(null);
        c.setActiveUntil(null);
        return c;
    }

    private void stubMysteryPool() {
        when(catalogRepo.findByAcquisition("MYSTERY_BOX")).thenReturn(java.util.List.of(
                catalogRow("HEADMASTER", "RARE"),
                catalogRow("GOLDSTAR", "SECRET")));
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
        stubMysteryPool();
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
    void openMysteryBox_emptyPool_returns503() {
        when(catalogRepo.findByAcquisition("MYSTERY_BOX"))
                .thenReturn(java.util.List.of());

        assertThatThrownBy(() -> shop.openMysteryBox(USER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Mystery box is closed");
        verify(userRepo, never()).spendStars(anyString(), anyInt());
    }

    @Test
    void openMysteryBox_insufficient_throwsBeforeUnlock() {
        stubMysteryPool();
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
        stubMysteryPool();
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

    /// True statistical test of the weighted-pull odds: 10000 draws on
    /// the Core pool should land within ±2% of the spec rates (15%/8%/2%).
    /// Catches regressions that flip the weighting back to "secret coin
    /// + uniform-among-remaining" (the old behaviour) where the rates
    /// would diverge.
    @Test
    void openMysteryBox_weightedPull_matchesSpecOdds() {
        // Stub the catalog with the exact Core composition.
        var commons = new java.util.ArrayList<
                com.pally.infrastructure.persistence.mochi.MochiCharacterJpaEntity>();
        for (var id : java.util.List.of(
                "PENCIL", "SCIENCE", "PE", "ART", "LUNCHBOX", "LIBRARY")) {
            commons.add(catalogRow(id, "COMMON"));
        }
        var rare = catalogRow("HEADMASTER", "RARE");
        var secret = catalogRow("GOLDSTAR", "SECRET");
        var pool = new java.util.ArrayList<
                com.pally.infrastructure.persistence.mochi.MochiCharacterJpaEntity>();
        pool.addAll(commons);
        pool.add(rare);
        pool.add(secret);
        when(catalogRepo.findByAcquisition("MYSTERY_BOX")).thenReturn(pool);

        // Stub the rest so the call doesn't blow up. Each draw treats
        // "stars=600", "no duplicate" so we always go down the
        // happy spend+unlock path.
        when(unlockRepo.existsByUserIdAndCharacter(eq(USER), anyString()))
                .thenReturn(false);
        when(userRepo.spendStars(USER, 600)).thenReturn(1);
        when(userRepo.findById(USER))
                .thenReturn(Optional.of(user(0, 0, 1)));

        final int draws = 10_000;
        var counts = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < draws; i++) {
            var result = shop.openMysteryBox(USER);
            counts.merge((String) result.get("character"), 1, Integer::sum);
        }

        int commonHits = 0;
        for (var id : java.util.List.of(
                "PENCIL", "SCIENCE", "PE", "ART", "LUNCHBOX", "LIBRARY")) {
            commonHits += counts.getOrDefault(id, 0);
        }
        int rareHits = counts.getOrDefault("HEADMASTER", 0);
        int secretHits = counts.getOrDefault("GOLDSTAR", 0);

        // 90% common ± 2% (180-pt window over 10000)
        assertThat(commonHits).isBetween(8800, 9200);
        // 8% rare  ± 1.5%
        assertThat(rareHits).isBetween(650, 950);
        // 2% secret ± 1%
        assertThat(secretHits).isBetween(100, 300);
    }

    @Test
    void mysteryBoxOdds_corePool_returns15_8_2() {
        var pool = new java.util.ArrayList<
                com.pally.infrastructure.persistence.mochi.MochiCharacterJpaEntity>();
        for (var id : java.util.List.of(
                "PENCIL", "SCIENCE", "PE", "ART", "LUNCHBOX", "LIBRARY")) {
            pool.add(catalogRow(id, "COMMON"));
        }
        pool.add(catalogRow("HEADMASTER", "RARE"));
        pool.add(catalogRow("GOLDSTAR", "SECRET"));
        when(catalogRepo.findByAcquisition("MYSTERY_BOX")).thenReturn(pool);

        var odds = shop.mysteryBoxOdds();
        assertThat(odds).hasSize(8);
        for (var entry : odds) {
            String rarity = (String) entry.get("rarity");
            long pct = (Long) (Object) entry.get("percent");
            switch (rarity) {
                case "COMMON" -> assertThat(pct).isEqualTo(15L);
                case "RARE"   -> assertThat(pct).isEqualTo(8L);
                case "SECRET" -> assertThat(pct).isEqualTo(2L);
                default -> org.assertj.core.api.Assertions.fail(
                        "unexpected rarity: " + rarity);
            }
        }
    }

    @Test
    void getCharacterUnlocks_readsCatalog_filtersActiveWindow() {
        // Catalog has 2 active + 1 expired.
        var active1 = catalogRow("ALPHA", "COMMON");
        active1.setAcquisition("DEFAULT");
        var active2 = catalogRow("BETA", "RARE");
        active2.setAcquisition("MYSTERY_BOX");
        var expired = catalogRow("GAMMA", "SECRET");
        expired.setAcquisition("SEASONAL");
        expired.setActiveUntil(java.time.Instant.now().minusSeconds(60));
        when(catalogRepo.findAll())
                .thenReturn(java.util.List.of(active1, active2, expired));
        when(unlockRepo.findByUserId(USER)).thenReturn(java.util.List.of());

        @SuppressWarnings("unchecked")
        var characters = (java.util.List<java.util.Map<String, Object>>)
                shop.getCharacterUnlocks(USER).get("characters");

        assertThat(characters).hasSize(2);
        assertThat(characters).extracting(c -> c.get("character"))
                .containsExactlyInAnyOrder("ALPHA", "BETA");
    }
}
