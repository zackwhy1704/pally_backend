package com.pally.domain.knowledge.usecase;

import java.util.List;

/**
 * Sealed result type for file upload operations.
 * Use pattern matching to handle each outcome without instanceof chains.
 */
public sealed interface UploadResult permits
        UploadResult.Success,
        UploadResult.RelevanceWarning,
        UploadResult.Failure {

    /**
     * @param wikiPageTitles titles of every wiki page produced or updated by
     *                       this upload (used by the post-upload "you
     *                       learned X" screen).
     * @param quality        OCR quality verdict: GOOD, BORDERLINE, or null for non-image uploads
     * @param qualityReason  human-readable reason for the quality verdict
     * @param extractedText  OCR-extracted text (only for image uploads, null for text/PDF)
     */
    record Success(String fileId, int pageCount, List<String> wikiPageTitles,
                   String quality, String qualityReason, String extractedText)
            implements UploadResult {

        /** Backward-compatible constructor for callers that don't need quality info. */
        public Success(String fileId, int pageCount, List<String> wikiPageTitles) {
            this(fileId, pageCount, wikiPageTitles, null, null, null);
        }
    }

    record RelevanceWarning(String fileId, double score, String reason) implements UploadResult {}

    record Failure(String message, Throwable cause) implements UploadResult {
        public Failure(String message) {
            this(message, null);
        }
    }
}
