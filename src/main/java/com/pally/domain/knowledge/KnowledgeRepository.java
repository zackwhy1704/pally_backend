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

    /**
     * Count a user's ACCEPTED uploads since {@code since} — files that reached
     * {@link KnowledgeFile.Status#READY} (i.e. triggered a compile). Excludes
     * dedup/relevance-rejected files (never READY), so a rejected upload never
     * burns the free-tier quota. Used by the upload-cap gate.
     */
    int countAcceptedUploadsSince(String userId, java.time.Instant since);
}
