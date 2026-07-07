package com.pally.domain.marking;

import java.time.Instant;
import java.util.List;

/**
 * Port for marking-correction persistence. Traffics only domain types — the JPA
 * adapter lives in {@code infrastructure/persistence/marking}.
 */
public interface MarkingCorrectionRepository {

    MarkingCorrection save(MarkingCorrection correction);

    java.util.Optional<MarkingCorrection> findById(String id);

    /** ACTIVE (not-removed) corrections for a class, newest first (teacher visibility, Part 4). */
    List<MarkingCorrection> findActiveByClassId(String classId);

    /**
     * Corrections eligible for the recompile feed: not yet compiled AND not removed
     * (Part 3 + Part 4 damper). A removed correction is excluded here, so it never
     * grounds a future draft.
     */
    List<MarkingCorrection> findUncompiledByClassId(String classId);

    /** Mark the given corrections as compiled at the given instant (Part 3). */
    void markCompiled(List<String> ids, Instant compiledAt);

    /** Soft-remove a correction (Part 4 damper): excluded from the feed + the view. */
    void markRemoved(String id, Instant removedAt);
}
