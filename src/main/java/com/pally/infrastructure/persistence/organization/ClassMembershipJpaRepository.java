package com.pally.infrastructure.persistence.organization;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClassMembershipJpaRepository extends JpaRepository<ClassMembershipJpaEntity, String> {

    List<ClassMembershipJpaEntity> findByClassId(String classId);

    List<ClassMembershipJpaEntity> findByUserId(String userId);

    Optional<ClassMembershipJpaEntity> findByClassIdAndUserId(String classId, String userId);

    long countByClassIdAndStatus(String classId, String status);

    long countByClassIdAndStatusAndRole(String classId, String status, String role);

    List<ClassMembershipJpaEntity> findByClassIdAndStatusAndRole(
            String classId, String status, String role);
}
