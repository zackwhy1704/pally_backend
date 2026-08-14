package com.pally.infrastructure.persistence.boss;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface BossInstanceJpaRepository extends JpaRepository<BossInstanceJpaEntity, String> {

    Optional<BossInstanceJpaEntity> findFirstByAvatarIdAndDefeatedFalse(String avatarId);
}
