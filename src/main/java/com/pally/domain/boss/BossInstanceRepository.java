package com.pally.domain.boss;

import java.util.Optional;

/** Port for persisting boss battles. The JPA adapter lives in infrastructure/persistence. */
public interface BossInstanceRepository {

    /** The avatar's current undefeated boss, if one exists — at most one at a time (v1). */
    Optional<BossInstance> findActiveByAvatarId(String avatarId);

    Optional<BossInstance> findById(String id);

    BossInstance save(BossInstance boss);
}
