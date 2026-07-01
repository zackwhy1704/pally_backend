package com.pally.domain.marking;

import java.util.Optional;

/**
 * Port for the (orgId, subject) → marking-corpus-avatar mapping. Traffics only
 * domain types; the JPA adapter lives in {@code infrastructure/persistence/marking}.
 */
public interface MarkingCorpusRepository {

    /** The marking-corpus mapping for this org+subject, if one exists yet. */
    Optional<MarkingCorpus> findByOrgIdAndSubject(String orgId, String subject);

    /** Persist a new mapping. Unique on (orgId, subject) at the DB level. */
    MarkingCorpus save(MarkingCorpus corpus);
}
