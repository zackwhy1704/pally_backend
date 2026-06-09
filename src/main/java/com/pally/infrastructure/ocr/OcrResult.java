package com.pally.infrastructure.ocr;

/**
 * Result of an OCR extraction, including which engine served the request.
 *
 * @param text       extracted plain text (never null, may be empty)
 * @param servedBy   engine name that produced the result (e.g. "claude-vision", "gemini-vision", "tesseract")
 * @param degraded   true if the primary engine failed and a fallback was used
 */
public record OcrResult(String text, String servedBy, boolean degraded) {

    public static OcrResult empty(String servedBy) {
        return new OcrResult("", servedBy, false);
    }

    public static OcrResult of(String text, String servedBy, boolean degraded) {
        return new OcrResult(text != null ? text : "", servedBy, degraded);
    }
}
