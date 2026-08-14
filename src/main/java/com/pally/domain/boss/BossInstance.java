package com.pally.domain.boss;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * A boss battle spawned from a detected weak topic. Server-authoritative: HP,
 * the current-question pointer, and the defeated/reward flags all live here —
 * the client only ever renders what this record says. {@code questionPoolJson}
 * is the server-only generated question set (Jackson-serialized {@code
 * List<QuizQuestion>}, INCLUDING the correct answer) that {@link
 * com.pally.domain.boss.BossBattleService} indexes with {@code currentIndex}
 * (wrapping via modulo) to pick each attempt's question.
 */
public record BossInstance(
        String id,
        String userId,
        String avatarId,
        String topicSlug,
        String questionPoolJson,
        int currentIndex,
        int hpRemaining,
        int hpMax,
        boolean defeated,
        boolean rewardUnlocked,
        Instant createdAt,
        Instant defeatedAt) {

    public static BossInstance spawn(String userId, String avatarId, String topicSlug,
                                      String questionPoolJson, int hp) {
        return new BossInstance(IdGenerator.newId(), userId, avatarId, topicSlug,
                questionPoolJson, 0, hp, hp, false, false, Instant.now(), null);
    }

    /** Applies one attack's outcome. A wrong answer still advances the pointer
     *  (next attempt gets the next question) but never reduces HP — the
     *  non-punitive counterattack is cosmetic-only on the client. */
    public BossInstance afterAttack(boolean hitLanded) {
        int newHp = hitLanded ? Math.max(0, hpRemaining - 1) : hpRemaining;
        boolean nowDefeated = newHp == 0;
        return new BossInstance(id, userId, avatarId, topicSlug, questionPoolJson,
                currentIndex + 1, newHp, hpMax, nowDefeated, nowDefeated,
                createdAt, nowDefeated ? Instant.now() : defeatedAt);
    }
}
