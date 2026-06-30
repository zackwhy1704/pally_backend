package com.pally.domain.marking;

import java.util.List;
import java.util.Optional;

/**
 * Port for marking-reference persistence. Traffics only domain types — the JPA
 * adapter lives in {@code infrastructure/persistence/marking}.
 */
public interface MarkingReferenceRepository {

    MarkingReference save(MarkingReference reference);

    Optional<MarkingReference> findById(String id);

    /** All marking references for a class, newest first. */
    List<MarkingReference> findByClassId(String classId);

    void deleteById(String id);
}
