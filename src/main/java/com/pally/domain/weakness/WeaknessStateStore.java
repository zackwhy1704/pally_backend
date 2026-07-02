package com.pally.domain.weakness;

import com.pally.domain.avatar.Subject;

import java.util.Optional;

/**
 * Persists the per-(userId, subject) debounce + wins state for the weakness
 * head. Domain port; the JPA adapter lives in infrastructure.
 */
public interface WeaknessStateStore {

    /**
     * @param weakSlugs  sorted, comma-joined current weak-topic slugs (debounce signature)
     * @param recentWins comma-joined topics that recently recovered (for "you improved" UI)
     */
    record WeaknessState(String weakSlugs, String recentWins) {}

    Optional<WeaknessState> find(String userId, Subject subject);

    void upsert(String userId, Subject subject, String weakSlugs, String recentWins);
}
