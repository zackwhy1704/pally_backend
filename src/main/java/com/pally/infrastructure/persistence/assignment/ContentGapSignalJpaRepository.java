package com.pally.infrastructure.persistence.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ContentGapSignalJpaRepository extends JpaRepository<ContentGapSignalJpaEntity, String> {

    List<ContentGapSignalJpaEntity> findByClassId(String classId);
}
