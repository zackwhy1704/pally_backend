package com.pally.domain.syllabus;

import java.time.Instant;

/**
 * A second (syllabus_code, topic_tag) under which an existing {@link SyllabusContentPack}
 * is discoverable — lets one pack's generated content be reused across syllabi with real
 * topic overlap (e.g. a G3 Computing "Algorithms" pack also serving Cambridge IGCSE's
 * "Algorithm Design and Problem-Solving") without duplicating generation. Purely additive
 * to the pack's own native (syllabus_code, topic_tag) from V129.
 */
public record SyllabusContentPackAlias(
        String id,
        String packId,
        String syllabusCode,
        String topicTag,
        Instant createdAt
) {
}
