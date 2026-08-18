package com.pally.domain.syllabus;

import java.util.List;

/**
 * Domain port for {@link SyllabusContentPackAlias} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/syllabus}.
 */
public interface SyllabusContentPackAliasRepository {

    /** Unique on (syllabus_code, topic_tag) at the DB level (V130) — same race pattern as packs. */
    SyllabusContentPackAlias save(SyllabusContentPackAlias alias);

    List<SyllabusContentPackAlias> findBySyllabusCode(String syllabusCode);

    List<SyllabusContentPackAlias> findByPackId(String packId);
}
