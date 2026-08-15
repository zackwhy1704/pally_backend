package com.pally.infrastructure.persistence.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassroomSessionJpaRepository extends JpaRepository<ClassroomSessionJpaEntity, String> {

    Optional<ClassroomSessionJpaEntity> findFirstByJoinCodeAndStatusNot(String joinCode, String status);

    boolean existsByJoinCodeAndStatusNot(String joinCode, String status);

    List<ClassroomSessionJpaEntity> findByClassIdAndStatusNotOrderByCreatedAtDesc(String classId, String status);
}
