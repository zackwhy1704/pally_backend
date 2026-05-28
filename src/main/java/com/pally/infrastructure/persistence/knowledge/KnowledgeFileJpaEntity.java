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

    @Column(name = "file_name", nullable = false)
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
        return e;
    }

    public KnowledgeFile toDomain() {
        return KnowledgeFile.reconstitute(
                id, avatarId, userId, fileName, storageKey, pageCount, uploadType,
                status, createdAt, extractedText
        );
    }
}
