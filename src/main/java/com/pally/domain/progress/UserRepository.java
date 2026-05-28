package com.pally.domain.progress;

import java.util.Optional;

public interface UserRepository {
    Optional<UserStats> findById(String userId);
    UserStats save(UserStats stats);
    void ensureUserExists(String userId);

    /// Atomically adds [xp] and [stars] to the user's totals. Recomputes level.
    void addXpAndStars(String userId, int xp, int stars);
}
