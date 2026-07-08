package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.KnowledgeFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface KnowledgeFileJpaRepository extends JpaRepository<KnowledgeFileJpaEntity, String> {

    List<KnowledgeFileJpaEntity> findByAvatarId(String avatarId);

    /** Child chunks of a SEGMENTED parent (picker + locked-chapter list). */
    List<KnowledgeFileJpaEntity> findByParentFileId(String parentFileId);

    /** Has this file already been segmented into child chunks? (sweep-guard idempotency) */
    boolean existsByParentFileId(String parentFileId);

    boolean existsByAvatarIdAndContentHashAndStatusNot(
            String avatarId, String contentHash, KnowledgeFile.Status status);

    /**
     * Accepted TOP-LEVEL uploads (not chunks) in the window — the doc-cap count.
     * A SEGMENTED parent counts as the one document uploaded; child chunks
     * (parent_file_id NOT NULL) never count as uploads, so chunking can't inflate
     * the upload quota. Statuses passed in = {READY, SEGMENTED}.
     */
    @Query("SELECT COUNT(k) FROM KnowledgeFileJpaEntity k WHERE k.userId = :userId "
            + "AND k.parentFileId IS NULL AND k.status IN :statuses AND k.createdAt > :since")
    int countAcceptedUploadsSince(@Param("userId") String userId,
                                  @Param("statuses") Collection<KnowledgeFile.Status> statuses,
                                  @Param("since") Instant since);

    /**
     * Chunk-compile allowance count: children that SUCCESSFULLY compiled
     * (compiled_at stamped) in the rolling window. Success-based, so a failed
     * compile (compiled_at NULL) never burns the student's allowance.
     */
    @Query("SELECT COUNT(k) FROM KnowledgeFileJpaEntity k WHERE k.userId = :userId "
            + "AND k.parentFileId IS NOT NULL AND k.compiledAt > :since")
    int countChunkCompilesSince(@Param("userId") String userId, @Param("since") Instant since);
}
