package com.pally.infrastructure.persistence.progress;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, String> {
    Optional<UserJpaEntity> findByEmail(String email);
    boolean existsByEmail(String email);

    Optional<UserJpaEntity> findByLinkCode(String linkCode);

    List<UserJpaEntity> findByParentId(String parentId);
}
