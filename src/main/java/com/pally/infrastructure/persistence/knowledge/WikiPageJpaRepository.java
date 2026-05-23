package com.pally.infrastructure.persistence.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WikiPageJpaRepository extends JpaRepository<WikiPageJpaEntity, String> {

    Optional<WikiPageJpaEntity> findByAvatarIdAndSlug(String avatarId, String slug);

    List<WikiPageJpaEntity> findByAvatarId(String avatarId);

    int countByAvatarId(String avatarId);

    void deleteByAvatarId(String avatarId);
}
