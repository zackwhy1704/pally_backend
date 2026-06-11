package com.pally.infrastructure.persistence.assignment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssignmentCompletionJpaRepository extends JpaRepository<AssignmentCompletionJpaEntity, String> {

    List<AssignmentCompletionJpaEntity> findByAssignmentId(String assignmentId);

    List<AssignmentCompletionJpaEntity> findByUserId(String userId);

    Optional<AssignmentCompletionJpaEntity> findByAssignmentIdAndUserId(String assignmentId, String userId);

    int countByAssignmentIdAndStatus(String assignmentId, String status);
}
