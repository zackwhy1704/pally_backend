package com.pally.domain.knowledge;

import java.util.List;
import java.util.Optional;

/**
 * Port for knowledge file persistence.
 */
public interface KnowledgeRepository {

    KnowledgeFile save(KnowledgeFile knowledgeFile);

    Optional<KnowledgeFile> findById(String id);

    List<KnowledgeFile> findByAvatarId(String avatarId);

    void deleteById(String id);
}
