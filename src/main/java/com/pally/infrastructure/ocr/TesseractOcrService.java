package com.pally.infrastructure.ocr;

import com.pally.domain.knowledge.port.OcrPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Image OCR service stub using Tess4J (Tesseract wrapper).
 *
 * <p>Implements {@link OcrPort} for image MIME types (anything starting with {@code image/}).
 * Also exposes a legacy {@link #extractText(InputStream)} method for backward compatibility
 * with {@link com.pally.domain.knowledge.usecase.UploadFileUseCase}.
 *
 * <p><strong>TODO:</strong> Integrate Tess4J for image OCR. Current implementation
 * returns an empty string and logs a warning.
 */
@Component
public class TesseractOcrService implements OcrPort {

    private static final Logger log = LoggerFactory.getLogger(TesseractOcrService.class);

    /**
     * Legacy method used by {@link com.pally.domain.knowledge.usecase.UploadFileUseCase}.
     *
     * @param inputStream image content stream
     * @return extracted text (currently always empty — TODO: implement Tess4J)
     */
    public String extractText(InputStream inputStream) {
        // TODO: Integrate Tess4J for image OCR
        // Example:
        //   Tesseract tesseract = new Tesseract();
        //   tesseract.setDatapath("/usr/share/tesseract-ocr/4.00/tessdata");
        //   tesseract.setLanguage("eng");
        //   BufferedImage image = ImageIO.read(inputStream);
        //   return tesseract.doOCR(image);
        log.warn("TesseractOcrService.extractText (InputStream) is a stub — Tess4J integration pending");
        return "";
    }

    /**
     * {@link OcrPort} implementation — extracts text from image bytes.
     *
     * @param fileBytes raw image bytes
     * @param mimeType  MIME type starting with {@code image/}
     * @return extracted text (currently always empty — TODO: implement Tess4J)
     */
    @Override
    public String extractText(byte[] fileBytes, String mimeType) {
        // TODO: Integrate Tess4J for image OCR
        // Example:
        //   Tesseract tesseract = new Tesseract();
        //   tesseract.setDatapath("/usr/share/tesseract-ocr/4.00/tessdata");
        //   tesseract.setLanguage("eng");
        //   ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes);
        //   BufferedImage image = ImageIO.read(bais);
        //   return tesseract.doOCR(image);
        log.warn("TesseractOcrService.extractText (byte[]) is a stub — Tess4J integration pending for mimeType={}", mimeType);
        return "";
    }
}
