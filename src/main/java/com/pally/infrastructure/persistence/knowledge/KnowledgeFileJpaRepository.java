package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.KnowledgeFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KnowledgeFileJpaRepository extends JpaRepository<KnowledgeFileJpaEntity, String> {

    List<KnowledgeFileJpaEntity> findByAvatarId(String avatarId);

    int countByUserIdAndStatusAndCreatedAtAfter(
            String userId, com.pally.domain.knowledge.KnowledgeFile.Status status, java.time.Instant since);

    boolean existsByAvatarIdAndContentHashAndStatusNot(
            String avatarId, String contentHash, KnowledgeFile.Status status);
}
