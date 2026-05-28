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
     */
    record Success(String fileId, int pageCount, List<String> wikiPageTitles)
            implements UploadResult {}

    record RelevanceWarning(String fileId, double score, String reason) implements UploadResult {}

    record Failure(String message, Throwable cause) implements UploadResult {
        public Failure(String message) {
            this(message, null);
        }
    }
}
