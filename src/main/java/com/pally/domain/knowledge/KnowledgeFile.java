package com.pally.domain.knowledge;

import com.pally.shared.util.IdGenerator;
import com.pally.shared.util.TextSanitizer;

import java.time.Instant;

/**
 * Domain entity representing an uploaded file (photo or PDF) attached to an avatar.
 */
public final class KnowledgeFile {

    public enum UploadType { PHOTO, PDF, TEXT }

    /**
     * PROCESSING → READY/FAILED/IRRELEVANT is the classic single-file lifecycle.
     * Chapter-chunking adds two COMPILE-IGNORED states (neither is READY, so the
     * executeBatched sweep and the recompile stale-detection both skip them):
     * <ul>
     *   <li>{@code SEGMENTED} — a PARENT whose oversized text was split into child
     *       chunks. It holds the full extracted text but is never compiled whole.</li>
     *   <li>{@code PENDING_CHUNK} — a CHILD chunk not yet picked. Picking it flips
     *       it to READY, at which point the normal compile sweep takes it.</li>
     * </ul>
     */
    public enum Status { PROCESSING, READY, FAILED, IRRELEVANT, SEGMENTED, PENDING_CHUNK }

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
    private String contentHash;  // SHA-256 of normalised extracted text
    private String ocrEngine;    // which OCR engine served (e.g. "claude-vision", "gemini-vision")
    private String compiledBy;   // which compiler tier served (e.g. "gemini-tier1-...", "haiku-chunked")
    private Instant compiledAt;  // when compiledBy was stamped — allowance window anchor
    // Chapter-chunk fields (null for ordinary, non-chunked files):
    private String parentFileId; // the SEGMENTED parent this chunk belongs to
    private Integer pageFrom;    // 1-based inclusive page range within the parent (PDF)
    private Integer pageTo;
    private String chunkTitle;   // chapter/section title for the picker row

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
        // Sanitize at the domain boundary so NO KnowledgeFile (parent OR chunk —
        // chunks are built via this constructor, not the setter) can carry a NUL /
        // control char into the extracted_text column (SQLState 22021 → 400).
        this.extractedText = TextSanitizer.stripUnstorableChars(extractedText);
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

    /**
     * Create a child CHUNK beneath a SEGMENTED parent. The chunk carries its OWN
     * text slice (so dedup — which hashes extracted text — never collides siblings)
     * and its page range. It starts PENDING_CHUNK (compile-ignored) with
     * {@code compiledBy == null}; picking it flips it to READY.
     */
    public static KnowledgeFile createChunk(
            KnowledgeFile parent, String chunkTitle, int pageFrom, int pageTo,
            int pageCount, String sliceText
    ) {
        KnowledgeFile c = new KnowledgeFile(
                IdGenerator.newId(), parent.avatarId, parent.userId,
                chunkTitle, parent.storageKey, pageCount, parent.uploadType,
                Status.PENDING_CHUNK, Instant.now(), sliceText);
        c.parentFileId = parent.id;
        c.chunkTitle = chunkTitle;
        c.pageFrom = pageFrom;
        c.pageTo = pageTo;
        return c;
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

    /** Parent transition: text was split into child chunks; never compiled whole. */
    public void markSegmented() {
        this.status = Status.SEGMENTED;
    }

    /**
     * Child transition: the student picked this chunk to compile. Flips PENDING_CHUNK
     * → READY so the normal per-avatar compile sweep takes it. No-op-safe to call on
     * an already-READY chunk (idempotent re-pick).
     */
    public void markPicked() {
        this.status = Status.READY;
    }

    /** Stamp the completion marker + its timestamp together (the allowance anchor). */
    public void markCompiled(String tier) {
        this.compiledBy = tier;
        this.compiledAt = Instant.now();
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
    public String getContentHash()       { return contentHash; }

    public void setExtractedText(String text) { this.extractedText = TextSanitizer.stripUnstorableChars(text); }
    public void setContentHash(String hash)   { this.contentHash = hash; }
    public String getOcrEngine()              { return ocrEngine; }
    public void setOcrEngine(String engine)   { this.ocrEngine = engine; }
    public String getCompiledBy()             { return compiledBy; }
    public void setCompiledBy(String tier)    { this.compiledBy = tier; }
    public Instant getCompiledAt()            { return compiledAt; }
    public void setCompiledAt(Instant at)     { this.compiledAt = at; }

    public String getParentFileId()           { return parentFileId; }
    public void setParentFileId(String id)    { this.parentFileId = id; }
    public Integer getPageFrom()              { return pageFrom; }
    public void setPageFrom(Integer p)        { this.pageFrom = p; }
    public Integer getPageTo()                { return pageTo; }
    public void setPageTo(Integer p)          { this.pageTo = p; }
    public String getChunkTitle()             { return chunkTitle; }
    public void setChunkTitle(String t)       { this.chunkTitle = t; }

    /** True when this row is a child chunk (has a SEGMENTED parent). */
    public boolean isChunk()                  { return parentFileId != null; }
}
