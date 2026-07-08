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

    /** Child chunks of a SEGMENTED parent (picker + locked-chapter list). */
    List<KnowledgeFile> findByParentFileId(String parentFileId);

    /** Has this file already been segmented into child chunks? Used by the sweep
     *  guard so an oversized parent is segmented exactly once (idempotent). */
    boolean hasChunks(String parentFileId);

    /**
     * Count a user's SUCCESSFUL chunk compiles since {@code since} — child chunks
     * (parent_file_id set) whose compile completed (compiled_at stamped). Success-
     * based so a failed compile never burns allowance. Used by the chunk-compile gate.
     */
    int countChunkCompilesSince(String userId, java.time.Instant since);

    void deleteById(String id);

    /**
     * Count a user's ACCEPTED uploads since {@code since} — files that reached
     * {@link KnowledgeFile.Status#READY} (i.e. triggered a compile). Excludes
     * dedup/relevance-rejected files (never READY), so a rejected upload never
     * burns the free-tier quota. Used by the upload-cap gate.
     */
    int countAcceptedUploadsSince(String userId, java.time.Instant since);
}
