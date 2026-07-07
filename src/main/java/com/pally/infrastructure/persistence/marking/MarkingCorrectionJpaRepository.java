package com.pally.infrastructure.persistence.marking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarkingCorrectionJpaRepository
        extends JpaRepository<MarkingCorrectionJpaEntity, String> {

    List<MarkingCorrectionJpaEntity> findByClassIdAndRemovedAtIsNullOrderByCapturedAtDesc(String classId);

    List<MarkingCorrectionJpaEntity>
        findByClassIdAndCompiledAtIsNullAndRemovedAtIsNullOrderByCapturedAtAsc(String classId);
}
