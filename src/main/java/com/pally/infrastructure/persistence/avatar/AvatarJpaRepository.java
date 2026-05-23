package com.pally.infrastructure.persistence.avatar;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AvatarJpaRepository extends JpaRepository<AvatarJpaEntity, String> {

    List<AvatarJpaEntity> findByUserId(String userId);

    boolean existsByIdAndUserId(String id, String userId);
}
