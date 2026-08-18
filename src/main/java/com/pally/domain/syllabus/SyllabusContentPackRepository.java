package com.pally.domain.syllabus;

import java.util.List;
import java.util.Optional;

/**
 * Domain port for {@link SyllabusContentPack} persistence.
 * The JPA adapter lives in {@code infrastructure/persistence/syllabus}.
 */
public interface SyllabusContentPackRepository {

    Optional<SyllabusContentPack> findBySyllabusCodeAndTopicTag(String syllabusCode, String topicTag);

    Optional<SyllabusContentPack> findById(String id);

    /** Unique on (syllabus_code, topic_tag) and on avatar_id at the DB level (V129). */
    SyllabusContentPack save(SyllabusContentPack pack);

    List<SyllabusContentPack> findByPackStatus(String packStatus);
}
