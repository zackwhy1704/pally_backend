package com.pally.infrastructure.persistence.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssignmentJpaRepository extends JpaRepository<AssignmentJpaEntity, String> {

    List<AssignmentJpaEntity> findByClassId(String classId);

    List<AssignmentJpaEntity> findByClassIdOrderByDueDateAsc(String classId);
}
