package com.pally.domain.knowledge.port;

/**
 * Port for extracting plain text from file bytes.
 */
public interface OcrPort {

    /**
     * Extracts text from the supplied file bytes.
     *
     * @param fileBytes raw bytes of the file
     * @param mimeType  MIME type of the file (e.g. "application/pdf", "image/png")
     * @return extracted plain text; never null, may be empty
     */
    String extractText(byte[] fileBytes, String mimeType);
}
