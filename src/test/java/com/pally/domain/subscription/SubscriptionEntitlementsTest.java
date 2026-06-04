package com.pally.domain.subscription;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that {@link Entitlements#forTier} produces the canonical
 * values from the Tier Table and that the unlimited sentinel works.
 */
class SubscriptionEntitlementsTest {

    // ── FREE (SPARK) ──────────────────────────────────────────────────────────

    @Test
    void freeTierLevel1_hasCorrectLimits() {
        var ent = Entitlements.forTier(SubscriptionTier.FREE, 1);
        assertThat(ent.chatsPerDay()).isEqualTo(20);
        assertThat(ent.maxMochis()).isEqualTo(1);
        assertThat(ent.maxStudents()).isEqualTo(1);
        assertThat(ent.parentDashboard()).isEqualTo(Entitlements.ParentDashboard.NONE);
        assertThat(ent.groups()).isFalse();
        assertThat(ent.priorityAi()).isFalse();
        assertThat(ent.quizFlashcards()).isTrue();
        assertThat(ent.studyPlan()).isTrue();
    }

    @Test
    void freeTierLevel5_mochiCapIsLevelGatedTo2() {
        var ent = Entitlements.forTier(SubscriptionTier.FREE, 5);
        assertThat(ent.maxMochis()).isEqualTo(2);
    }

    @Test
    void freeTierLevel4_mochiCapIsStill1() {
        var ent = Entitlements.forTier(SubscriptionTier.FREE, 4);
        assertThat(ent.maxMochis()).isEqualTo(1);
    }

    // ── PRO ───────────────────────────────────────────────────────────────────

    @Test
    void proTier_hasCorrectLimits() {
        var ent = Entitlements.forTier(SubscriptionTier.PRO);
        assertThat(ent.chatsPerDay()).isEqualTo(100);
        assertThat(ent.maxMochis()).isEqualTo(5);
        assertThat(ent.maxStudents()).isEqualTo(1);
        assertThat(ent.parentDashboard()).isEqualTo(Entitlements.ParentDashboard.FAMILY_WIDE);
        assertThat(ent.groups()).isTrue();
        assertThat(ent.priorityAi()).isFalse();
        assertThat(ent.quizFlashcards()).isTrue();
        assertThat(ent.studyPlan()).isTrue();
    }

    // ── MAX ───────────────────────────────────────────────────────────────────

    @Test
    void maxTier_hasCorrectLimits() {
        var ent = Entitlements.forTier(SubscriptionTier.MAX);
        assertThat(ent.chatsPerDay()).isEqualTo(-1);
        assertThat(ent.maxMochis()).isEqualTo(-1);
        assertThat(ent.maxStudents()).isEqualTo(1);
        assertThat(ent.parentDashboard()).isEqualTo(Entitlements.ParentDashboard.FAMILY_WIDE);
        assertThat(ent.groups()).isTrue();
        assertThat(ent.priorityAi()).isTrue();
    }

    @Test
    void maxTier_chatsAndMochisAreUnlimited() {
        var ent = Entitlements.forTier(SubscriptionTier.MAX);
        assertThat(Entitlements.isUnlimited(ent.chatsPerDay())).isTrue();
        assertThat(Entitlements.isUnlimited(ent.maxMochis())).isTrue();
    }

    // ── FAMILY ────────────────────────────────────────────────────────────────

    @Test
    void familyTier_hasCorrectLimits() {
        var ent = Entitlements.forTier(SubscriptionTier.FAMILY);
        assertThat(ent.chatsPerDay()).isEqualTo(-1);
        assertThat(ent.maxMochis()).isEqualTo(-1);
        assertThat(ent.maxStudents()).isEqualTo(4);
        assertThat(ent.parentDashboard()).isEqualTo(Entitlements.ParentDashboard.FAMILY_WIDE);
        assertThat(ent.groups()).isTrue();
        assertThat(ent.priorityAi()).isTrue();
    }

    // ── CENTRE ────────────────────────────────────────────────────────────────

    @Test
    void centreTier_hasCorrectLimits() {
        var ent = Entitlements.forTier(SubscriptionTier.CENTRE);
        assertThat(ent.maxStudents()).isEqualTo(15);
        assertThat(ent.parentDashboard()).isEqualTo(Entitlements.ParentDashboard.PER_STUDENT);
        assertThat(ent.groups()).isTrue();
        assertThat(ent.priorityAi()).isTrue();
    }

    // ── isUnlimited sentinel ──────────────────────────────────────────────────

    @Test
    void isUnlimited_minusOne_returnsTrue() {
        assertThat(Entitlements.isUnlimited(-1)).isTrue();
    }

    @Test
    void isUnlimited_positiveValue_returnsFalse() {
        assertThat(Entitlements.isUnlimited(100)).isFalse();
        assertThat(Entitlements.isUnlimited(0)).isFalse();
        assertThat(Entitlements.isUnlimited(1)).isFalse();
    }
}
