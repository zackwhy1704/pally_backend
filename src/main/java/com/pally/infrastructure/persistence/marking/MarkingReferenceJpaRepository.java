package com.pally.infrastructure.persistence.marking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarkingReferenceJpaRepository
        extends JpaRepository<MarkingReferenceJpaEntity, String> {

    List<MarkingReferenceJpaEntity> findByClassIdOrderByCreatedAtDesc(String classId);
}
