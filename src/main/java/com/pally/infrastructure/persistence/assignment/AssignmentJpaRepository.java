package com.pally.infrastructure.persistence.assignment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AssignmentJpaRepository extends JpaRepository<AssignmentJpaEntity, String> {

    List<AssignmentJpaEntity> findByClassId(String classId);

    List<AssignmentJpaEntity> findByClassIdOrderByDueDateAsc(String classId);

    List<AssignmentJpaEntity> findByStudentIdOrderByDueDateAsc(String studentId);

    /// Account deletion: assignment.student_id → users has no ON DELETE, so a
    /// parent-assigned revision for this student would abort the delete transaction.
    @Modifying
    @Query("DELETE FROM AssignmentJpaEntity a WHERE a.studentId = :u")
    void deleteByStudentId(@Param("u") String userId);
}
