package com.pally.infrastructure.persistence.curriculum;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CurriculumTopicJpaRepository
        extends JpaRepository<CurriculumTopicJpaEntity, String> {

    List<CurriculumTopicJpaEntity> findByCurriculumIdOrderBySequenceAsc(
            String curriculumId);
}
