package com.pally.infrastructure.persistence.knowledge;

import com.pally.domain.knowledge.KnowledgeFile;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "knowledge_files")
@Getter
@Setter
@NoArgsConstructor
public class KnowledgeFileJpaEntity {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "avatar_id", nullable = false, length = 36)
    private String avatarId;

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    // TEXT (V122): chunk titles from PDF bookmarks reuse this column and can exceed 255.
    @Column(name = "file_name", nullable = false, columnDefinition = "TEXT")
    private String fileName;

    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "upload_type", nullable = false, length = 20)
    private KnowledgeFile.UploadType uploadType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private KnowledgeFile.Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "extracted_text", columnDefinition = "TEXT")
    private String extractedText;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "ocr_engine", length = 30)
    private String ocrEngine;

    @Column(name = "compiled_by", length = 30)
    private String compiledBy;

    @Column(name = "compiled_at")
    private Instant compiledAt;

    @Column(name = "parent_file_id", length = 36)
    private String parentFileId;

    @Column(name = "page_from")
    private Integer pageFrom;

    @Column(name = "page_to")
    private Integer pageTo;

    @Column(name = "chunk_title", columnDefinition = "TEXT") // TEXT (V122): bookmark titles can exceed 255
    private String chunkTitle;

    public static KnowledgeFileJpaEntity fromDomain(KnowledgeFile kf) {
        KnowledgeFileJpaEntity e = new KnowledgeFileJpaEntity();
        e.id = kf.getId();
        e.avatarId = kf.getAvatarId();
        e.userId = kf.getUserId();
        e.fileName = kf.getFileName();
        e.storageKey = kf.getStorageKey();
        e.pageCount = kf.getPageCount();
        e.uploadType = kf.getUploadType();
        e.status = kf.getStatus();
        e.createdAt = kf.getCreatedAt();
        e.extractedText = kf.getExtractedText();
        e.contentHash   = kf.getContentHash();
        e.ocrEngine     = kf.getOcrEngine();
        e.compiledBy    = kf.getCompiledBy();
        e.compiledAt    = kf.getCompiledAt();
        e.parentFileId  = kf.getParentFileId();
        e.pageFrom      = kf.getPageFrom();
        e.pageTo        = kf.getPageTo();
        e.chunkTitle    = kf.getChunkTitle();
        return e;
    }

    public KnowledgeFile toDomain() {
        KnowledgeFile kf = KnowledgeFile.reconstitute(
                id, avatarId, userId, fileName, storageKey, pageCount, uploadType,
                status, createdAt, extractedText);
        kf.setContentHash(contentHash);
        kf.setOcrEngine(ocrEngine);
        kf.setCompiledBy(compiledBy);
        kf.setCompiledAt(compiledAt);
        kf.setParentFileId(parentFileId);
        kf.setPageFrom(pageFrom);
        kf.setPageTo(pageTo);
        kf.setChunkTitle(chunkTitle);
        return kf;
    }

    // Explicit getters for use in ContentDeduplicator queries
    public String getFileName()          { return fileName; }
    public String getContentHash()       { return contentHash; }
    public KnowledgeFile.Status getStatus() { return status; }
    public String getExtractedText()     { return extractedText; }
}
