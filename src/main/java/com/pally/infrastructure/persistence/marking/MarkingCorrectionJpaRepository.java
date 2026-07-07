package com.pally.infrastructure.persistence.marking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MarkingCorrectionJpaRepository
        extends JpaRepository<MarkingCorrectionJpaEntity, String> {

    List<MarkingCorrectionJpaEntity> findByClassIdOrderByCapturedAtDesc(String classId);

    List<MarkingCorrectionJpaEntity> findByClassIdAndCompiledAtIsNullOrderByCapturedAtAsc(String classId);
}
