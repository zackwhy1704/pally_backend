package com.pally.infrastructure.persistence.weakness;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WeaknessProfileStateJpaRepository
        extends JpaRepository<WeaknessProfileStateJpaEntity, String> {

    Optional<WeaknessProfileStateJpaEntity> findByUserIdAndSubject(String userId, String subject);
}
