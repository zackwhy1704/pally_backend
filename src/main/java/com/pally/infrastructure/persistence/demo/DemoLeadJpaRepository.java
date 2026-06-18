package com.pally.infrastructure.persistence.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DemoLeadJpaRepository extends JpaRepository<DemoLeadJpaEntity, String> {

    List<DemoLeadJpaEntity> findAllByOrderByCreatedAtDesc();

    List<DemoLeadJpaEntity> findByStatus(String status);
}
