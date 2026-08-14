package com.pally.infrastructure.persistence.learning;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LearningEventJpaRepository extends JpaRepository<LearningEventJpaEntity, String> {
}
