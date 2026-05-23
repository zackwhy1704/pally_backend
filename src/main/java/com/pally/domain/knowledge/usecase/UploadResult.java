package com.pally.domain.knowledge.usecase;

/**
 * Sealed result type for file upload operations.
 * Use pattern matching to handle each outcome without instanceof chains.
 */
public sealed interface UploadResult permits
        UploadResult.Success,
        UploadResult.RelevanceWarning,
        UploadResult.Failure {

    record Success(String fileId, int pageCount) implements UploadResult {}

    record RelevanceWarning(String fileId, double score, String reason) implements UploadResult {}

    record Failure(String message, Throwable cause) implements UploadResult {
        public Failure(String message) {
            this(message, null);
        }
    }
}
