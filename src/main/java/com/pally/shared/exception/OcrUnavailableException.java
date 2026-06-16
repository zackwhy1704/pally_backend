package com.pally.shared.exception;

/**
 * Thrown when all available OCR engines (Claude Vision, Gemini Vision) are unable to
 * extract text from an uploaded image. The caller should surface this as a 422
 * Unprocessable Entity — the file is structurally valid but we cannot read it.
 */
public class OcrUnavailableException extends RuntimeException {

    public OcrUnavailableException(String message) {
        super(message);
    }

    public OcrUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
