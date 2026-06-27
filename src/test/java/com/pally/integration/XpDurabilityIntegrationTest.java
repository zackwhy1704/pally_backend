package com.pally.integration;

import com.pally.domain.progress.StreakService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the XP-durability fix: {@code creditXpAndStars} uses
 * {@code @Modifying(clearAutomatically=true)} so the JPA first-level cache is
 * evicted after the bulk UPDATE. A subsequent {@code StreakService.recordActiveDay}
 * call — which loads the user entity and saves it back — must therefore see the
 * updated XP value (30), not the stale pre-update snapshot (0).
 *
 * <p>Without the fix, the streak service loads xp=0 from cache, saves the entity
 * with xp=0, and overwrites the bulk UPDATE — silently zeroing the student's XP
 * credit on every quiz submit while returning xp=30 in the response body.
 */
class XpDurabilityIntegrationTest extends IntegrationTestBase {

    @Autowired UserRepository userRepository;
    @Autowired StreakService streakService;

    @Test
    @Transactional
    void xpCredit_survivesSubsequentStreakEntitySave_inSameTx() {
        // Create a real user in the DB (the same path as quiz submit).
        AuthResult auth = registerConsentedUser(
                "xp-durability-" + System.nanoTime() + "@test.com", "password123");
        String userId = auth.userId();

        User before = userRepository.findById(userId).orElseThrow();
        assertThat(before.getXp()).as("fresh user starts at 0 XP").isZero();

        // Step 1 — replicate the XP credit from XpService.awardForQuiz:
        // native bulk UPDATE (xp = xp + 30).  With clearAutomatically=true
        // this evicts the user entity from the first-level cache.
        userRepository.addXpAndStars(userId, 30, 0);

        // Step 2 — replicate StreakService.recordActiveDay called later in the
        // same @Transactional execute() scope: loads user via findById + saves
        // entity back with updated streakDays.  Without the fix, this step
        // loaded the stale entity (xp=0) and wrote it back, zeroing the XP.
        streakService.recordActiveDay(userId);

        // Step 3 — assert both writes landed.
        User after = userRepository.findById(userId).orElseThrow();
        assertThat(after.getXp())
                .as("XP credit must survive a streak entity-save in the same TX "
                    + "(clearAutomatically=true ensures the streak service reads "
                    + "fresh xp=30, not stale xp=0 from the JPA cache)")
                .isEqualTo(30);
        assertThat(after.getStreakDays())
                .as("streak must also increment to 1")
                .isEqualTo(1);
    }
}
