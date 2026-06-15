package com.pally.domain.module;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link MuddiestVote} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/module}.
 */
public interface MuddiestVoteRepository {

    MuddiestVote save(MuddiestVote vote);

    Optional<MuddiestVote> findByModuleIdAndUserId(String moduleId, String userId);

    long countByModuleId(String moduleId);

    /**
     * Returns rows of [conceptId, count] ordered by count descending.
     * The Object[] layout is identical to the JPA repository query so
     * callers need no changes.
     */
    List<Object[]> countByConcept(String moduleId);
}
