package com.pally.infrastructure.ocr;

import com.pally.domain.homework.DocumentTextExtractionPort;
import com.pally.domain.knowledge.port.OcrPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Routes a homework file's bytes to the right extractor by MIME type:
 * image → vision OCR (the {@code @Primary} {@link OcrPort}, i.e.
 * {@link ResilientOcrService}), PDF → text strip ({@link PdfTextExtractor}),
 * plain text → UTF-8 decode. Best-effort: any failure (or an unsupported type)
 * yields "" so a submission still succeeds and the teacher can read the artifact
 * directly.
 *
 * <p>The image path is typed to the {@code OcrPort} interface (not the concrete
 * service) so test contexts that {@code @MockBean OcrPort} wire cleanly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentTextExtractor implements DocumentTextExtractionPort {

    private final OcrPort ocrService;
    private final PdfTextExtractor pdfTextExtractor;

    @Override
    public String extract(byte[] bytes, String mimeType) {
        if (bytes == null || bytes.length == 0) return "";
        String mime = mimeType == null ? "" : mimeType.toLowerCase();
        try {
            if (mime.equals("application/pdf")) {
                return safe(pdfTextExtractor.extractText(bytes, mime));
            }
            if (mime.startsWith("image/")) {
                return safe(ocrService.extractText(bytes, mime));
            }
            if (mime.startsWith("text/")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            log.info("[Homework] no extractor for mime={} — leaving work text empty", mime);
            return "";
        } catch (Exception e) {
            // Unreadable handwriting, a corrupt PDF, OCR provider down — none of
            // these should fail the upload; the teacher can still mark by hand.
            log.warn("[Homework] text extraction failed for mime={}: {}", mime, e.toString());
            return "";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
