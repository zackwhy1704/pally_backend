package com.pally.domain.marking;

import java.time.Instant;
import java.util.List;

/**
 * Port for marking-correction persistence. Traffics only domain types — the JPA
 * adapter lives in {@code infrastructure/persistence/marking}.
 */
public interface MarkingCorrectionRepository {

    MarkingCorrection save(MarkingCorrection correction);

    /** All corrections for a class, newest first (teacher visibility, Part 4). */
    List<MarkingCorrection> findByClassId(String classId);

    /** Corrections not yet compiled into the marking-wiki (Part 3, debounced recompile). */
    List<MarkingCorrection> findUncompiledByClassId(String classId);

    /** Mark the given corrections as compiled at the given instant (Part 3). */
    void markCompiled(List<String> ids, Instant compiledAt);
}
