package com.pally.infrastructure.persistence.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeFileJpaRepository extends JpaRepository<KnowledgeFileJpaEntity, String> {

    List<KnowledgeFileJpaEntity> findByAvatarId(String avatarId);
}
