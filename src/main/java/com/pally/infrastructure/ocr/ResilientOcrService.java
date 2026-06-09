package com.pally.infrastructure.ocr;

import com.pally.domain.knowledge.port.OcrPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Composite OCR service that chains: Claude Vision -> Gemini Vision -> Tesseract (stub).
 *
 * <p>Marked {@code @Primary} so all {@link OcrPort} injection points get the
 * resilient chain instead of a single engine. Quality guard: if extracted text
 * is suspiciously short (<10 chars) for an image >10KB, the next engine is tried.
 *
 * <p>Records which engine actually served via {@link #getLastResult()}, which
 * callers can use to set the {@code ocrEngine} field on KnowledgeFile.
 */
@Primary
@Component
public class ResilientOcrService implements OcrPort {

    private static final Logger log =
            LoggerFactory.getLogger(ResilientOcrService.class);

    /** Minimum chars expected from a non-trivial image (>10KB). */
    private static final int QUALITY_MIN_CHARS = 10;
    /** Image size threshold (bytes) above which we expect meaningful text. */
    private static final int QUALITY_MIN_IMAGE_BYTES = 10_240;

    private final ClaudeVisionOcrService claudeOcr;
    private final GeminiVisionOcrService geminiOcr;
    private final TesseractOcrService tesseractOcr;

    /** ThreadLocal to avoid thread-safety issues when returning which engine served. */
    private final ThreadLocal<OcrResult> lastResult = new ThreadLocal<>();

    public ResilientOcrService(
            ClaudeVisionOcrService claudeOcr,
            GeminiVisionOcrService geminiOcr,
            TesseractOcrService tesseractOcr) {
        this.claudeOcr = claudeOcr;
        this.geminiOcr = geminiOcr;
        this.tesseractOcr = tesseractOcr;
    }

    @Override
    public String extractText(byte[] fileBytes, String mimeType) {
        if (fileBytes == null || fileBytes.length == 0) {
            lastResult.set(OcrResult.empty("none"));
            return "";
        }

        boolean largeImage = fileBytes.length > QUALITY_MIN_IMAGE_BYTES;

        // ── Engine 1: Claude Vision (primary) ───────────────────────────────
        try {
            String text = claudeOcr.extractText(fileBytes, mimeType);
            if (isAcceptable(text, largeImage)) {
                OcrResult result = OcrResult.of(text, "claude-vision", false);
                lastResult.set(result);
                log.info("[ResilientOCR] Claude served: {} chars", text.length());
                return text;
            }
            log.warn("[ResilientOCR] Claude returned insufficient text ({} chars) — trying Gemini",
                    text.length());
        } catch (Exception e) {
            log.warn("[ResilientOCR] Claude OCR failed: {} — trying Gemini",
                    e.getMessage());
        }

        // ── Engine 2: Gemini Vision (fallback) ──────────────────────────────
        if (geminiOcr.isAvailable()) {
            try {
                String text = geminiOcr.extractText(fileBytes, mimeType);
                if (isAcceptable(text, largeImage)) {
                    OcrResult result = OcrResult.of(text, "gemini-vision", true);
                    lastResult.set(result);
                    log.info("[ResilientOCR] Gemini served (degraded): {} chars", text.length());
                    return text;
                }
                log.warn("[ResilientOCR] Gemini returned insufficient text ({} chars) — trying Tesseract",
                        text.length());
            } catch (Exception e) {
                log.warn("[ResilientOCR] Gemini OCR failed: {} — trying Tesseract",
                        e.getMessage());
            }
        } else {
            log.debug("[ResilientOCR] Gemini not available (no API key) — trying Tesseract");
        }

        // ── Engine 3: Tesseract (last resort, stub) ─────────────────────────
        try {
            String text = tesseractOcr.extractText(fileBytes, mimeType);
            OcrResult result = OcrResult.of(text, "tesseract", true);
            lastResult.set(result);
            if (!text.isEmpty()) {
                log.info("[ResilientOCR] Tesseract served (degraded): {} chars", text.length());
            } else {
                log.warn("[ResilientOCR] All 3 OCR engines failed or returned empty text");
            }
            return text;
        } catch (Exception e) {
            log.error("[ResilientOCR] All OCR engines failed", e);
            lastResult.set(OcrResult.empty("all-failed"));
            return "";
        }
    }

    /**
     * Returns the result metadata from the most recent call on this thread.
     * Callers should read this immediately after {@link #extractText}.
     */
    public OcrResult getLastResult() {
        OcrResult r = lastResult.get();
        return r != null ? r : OcrResult.empty("unknown");
    }

    /**
     * Quality guard: for large images (>10KB), we expect at least 10 chars
     * of extracted text. Small images (icons, diagrams with no text) may
     * legitimately produce empty results.
     */
    private boolean isAcceptable(String text, boolean largeImage) {
        if (text == null || text.isEmpty()) return false;
        if (largeImage && text.length() < QUALITY_MIN_CHARS) return false;
        return true;
    }
}
