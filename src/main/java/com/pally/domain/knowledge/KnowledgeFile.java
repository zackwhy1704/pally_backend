package com.pally.domain.knowledge;

import com.pally.shared.util.IdGenerator;

import java.time.Instant;

/**
 * Domain entity representing an uploaded file (photo or PDF) attached to an avatar.
 */
public final class KnowledgeFile {

    public enum UploadType { PHOTO, PDF }

    public enum Status { PROCESSING, READY, FAILED, IRRELEVANT }

    private final String id;
    private final String avatarId;
    private final String userId;
    private final String fileName;
    private final String storageKey;
    private int pageCount;
    private final UploadType uploadType;
    private Status status;
    private final Instant createdAt;
    private String extractedText;

    private KnowledgeFile(
            String id, String avatarId, String userId, String fileName,
            String storageKey, int pageCount, UploadType uploadType,
            Status status, Instant createdAt, String extractedText
    ) {
        this.id = id;
        this.avatarId = avatarId;
        this.userId = userId;
        this.fileName = fileName;
        this.storageKey = storageKey;
        this.pageCount = pageCount;
        this.uploadType = uploadType;
        this.status = status;
        this.createdAt = createdAt;
        this.extractedText = extractedText;
    }

    public static KnowledgeFile create(
            String avatarId, String userId, String fileName,
            String storageKey, UploadType uploadType
    ) {
        return new KnowledgeFile(
                IdGenerator.newId(), avatarId, userId, fileName,
                storageKey, 0, uploadType, Status.PROCESSING, Instant.now(), null
        );
    }

    public static KnowledgeFile reconstitute(
            String id, String avatarId, String userId, String fileName,
            String storageKey, int pageCount, UploadType uploadType,
            Status status, Instant createdAt
    ) {
        return new KnowledgeFile(id, avatarId, userId, fileName,
                storageKey, pageCount, uploadType, status, createdAt, null);
    }

    public static KnowledgeFile reconstitute(
            String id, String avatarId, String userId, String fileName,
            String storageKey, int pageCount, UploadType uploadType,
            Status status, Instant createdAt, String extractedText
    ) {
        return new KnowledgeFile(id, avatarId, userId, fileName,
                storageKey, pageCount, uploadType, status, createdAt, extractedText);
    }

    public void markReady(int pageCount) {
        this.pageCount = pageCount;
        this.status = Status.READY;
    }

    public void markFailed() {
        this.status = Status.FAILED;
    }

    public void markIrrelevant() {
        this.status = Status.IRRELEVANT;
    }

    public String getId()                { return id; }
    public String getAvatarId()          { return avatarId; }
    public String getUserId()            { return userId; }
    public String getFileName()          { return fileName; }
    public String getStorageKey()        { return storageKey; }
    public int getPageCount()            { return pageCount; }
    public UploadType getUploadType()    { return uploadType; }
    public Status getStatus()            { return status; }
    public Instant getCreatedAt()        { return createdAt; }
    public String getExtractedText()     { return extractedText; }

    public void setExtractedText(String text) { this.extractedText = text; }
}
