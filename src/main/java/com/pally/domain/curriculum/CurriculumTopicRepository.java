package com.pally.domain.curriculum;

import java.util.List;

/**
 * Domain port for {@link CurriculumTopic} persistence.
 * Implemented by infrastructure adapters; never import JPA here.
 */
public interface CurriculumTopicRepository {

    List<CurriculumTopic> findByCurriculumIdOrderBySequenceAsc(String curriculumId);

    CurriculumTopic save(CurriculumTopic topic);
}
