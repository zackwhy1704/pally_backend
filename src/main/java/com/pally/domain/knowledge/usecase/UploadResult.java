package com.pally.domain.knowledge.usecase;

import java.util.List;

/**
 * Sealed result type for file upload operations.
 * Use pattern matching to handle each outcome without instanceof chains.
 */
public sealed interface UploadResult permits
        UploadResult.Success,
        UploadResult.Segmented,
        UploadResult.RelevanceWarning,
        UploadResult.Failure {

    /**
     * @param wikiPageTitles titles of every wiki page produced or updated by
     *                       this upload (used by the post-upload "you
     *                       learned X" screen).
     * @param quality        OCR quality verdict: GOOD, BORDERLINE, or null for non-image uploads
     * @param qualityReason  human-readable reason for the quality verdict
     * @param extractedText  OCR-extracted text (only for image uploads, null for text/PDF)
     * @param extractedChars how many characters of text we extracted from this file.
     *                       The client uses this to warn "we couldn't read much —
     *                       it won't train well" for low-but-nonzero extractions
     *                       (a truly empty extraction already fails the upload).
     */
    record Success(String fileId, int pageCount, List<String> wikiPageTitles,
                   String quality, String qualityReason, String extractedText,
                   int extractedChars)
            implements UploadResult {

        /** Backward-compatible constructor for callers that don't need quality info. */
        public Success(String fileId, int pageCount, List<String> wikiPageTitles) {
            this(fileId, pageCount, wikiPageTitles, null, null, null, 0);
        }
    }

    /**
     * The upload was large (>segment trigger) and valid, so it was split into
     * pickable chunks instead of compiling whole. NOTHING has compiled — each
     * chunk is PENDING_CHUNK until the student picks it. The client shows the
     * chapter picker from {@code chunks}; the compile-allowance counter is read
     * separately (the chapters/entitlement endpoint) so it stays a single source.
     *
     * @param parentFileId the SEGMENTED parent holding the full text
     * @param chunks       ordered chapter descriptors for the picker
     */
    record Segmented(String parentFileId, List<ChunkInfo> chunks) implements UploadResult {}

    /** One pickable chapter row. {@code pageFrom/pageTo} are 1-based inclusive. */
    record ChunkInfo(String chunkId, String title, int pageFrom, int pageTo, int pageCount) {}

    record RelevanceWarning(String fileId, double score, String reason) implements UploadResult {}

    record Failure(String message, Throwable cause) implements UploadResult {
        public Failure(String message) {
            this(message, null);
        }
    }
}
