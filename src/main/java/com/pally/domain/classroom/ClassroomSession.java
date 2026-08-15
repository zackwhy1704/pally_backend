package com.pally.domain.classroom;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * A teacher-created live shared boss battle for a whole class. Server-
 * authoritative — same as {@link com.pally.domain.boss.BossInstance}, which
 * this record's fields and HP/question-pool semantics deliberately mirror
 * ({@code afterHit} is the same "HP only drops on a correct hit, the pointer
 * always advances" algorithm as {@code BossInstance.afterAttack}), just keyed
 * by a class-wide session instead of one (userId, avatarId) pair.
 *
 * <p>Carries NO participant identity — nicknames are ephemeral, held only in
 * {@link ClassroomEventBus} for the session's lifetime.
 */
public record ClassroomSession(
        String id,
        String classId,
        String teacherId,
        String avatarId, // the class corpus avatar these questions were generated from
        String joinCode,
        String topicSlug,
        String questionPoolJson,
        int currentIndex,
        int hpRemaining,
        int hpMax,
        boolean defeated,
        String status, // CREATED | ACTIVE | ENDED
        Instant createdAt,
        Instant startedAt,
        Instant endedAt) {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ENDED = "ENDED";

    public static ClassroomSession create(String classId, String teacherId, String avatarId,
                                           String joinCode, String topicSlug,
                                           String questionPoolJson, int hp) {
        return new ClassroomSession(IdGenerator.newId(), classId, teacherId, avatarId, joinCode,
                topicSlug, questionPoolJson, 0, hp, hp, false, STATUS_CREATED, Instant.now(),
                null, null);
    }

    public ClassroomSession started() {
        return new ClassroomSession(id, classId, teacherId, avatarId, joinCode, topicSlug,
                questionPoolJson, currentIndex, hpRemaining, hpMax, defeated, STATUS_ACTIVE,
                createdAt, Instant.now(), endedAt);
    }

    public ClassroomSession ended() {
        return new ClassroomSession(id, classId, teacherId, avatarId, joinCode, topicSlug,
                questionPoolJson, currentIndex, hpRemaining, hpMax, defeated, STATUS_ENDED,
                createdAt, startedAt, Instant.now());
    }

    /** Same algorithm as {@code BossInstance.afterAttack}: a wrong answer still
     *  advances the pointer (next attempt gets the next question) but never
     *  reduces HP. */
    public ClassroomSession afterHit(boolean hitLanded) {
        int newHp = hitLanded ? Math.max(0, hpRemaining - 1) : hpRemaining;
        boolean nowDefeated = newHp == 0;
        return new ClassroomSession(id, classId, teacherId, avatarId, joinCode, topicSlug,
                questionPoolJson, currentIndex + 1, newHp, hpMax, nowDefeated, status,
                createdAt, startedAt, endedAt);
    }
}
