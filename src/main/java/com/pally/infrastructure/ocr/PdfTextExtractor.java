package com.pally.infrastructure.ocr;

import com.pally.domain.knowledge.port.OcrPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * PDF text extraction using Apache PDFBox.
 *
 * <p>Implements {@link OcrPort} for {@code application/pdf} MIME type.
 * Also exposes a legacy {@link #extract(InputStream)} method returning
 * a {@link PdfExtractionResult} record with both extracted text and page count,
 * for use by {@link com.pally.domain.knowledge.usecase.UploadFileUseCase}.
 */
@Component
public class PdfTextExtractor implements OcrPort {

    private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

    /**
     * Result of a PDF extraction containing both the text and the page count.
     *
     * @param text      full extracted plain text
     * @param pageCount number of pages in the PDF
     */
    public record PdfExtractionResult(String text, int pageCount) {}

    /**
     * Extracts all text from a PDF input stream.
     *
     * @param inputStream PDF content (caller is responsible for closing)
     * @return {@link PdfExtractionResult} with extracted text and page count
     * @throws IOException if the PDF cannot be read or parsed
     */
    public PdfExtractionResult extract(InputStream inputStream) throws IOException {
        try (PDDocument document = Loader.loadPDF(inputStream.readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            int pageCount = document.getNumberOfPages();
            log.debug("Extracted {} chars from {} PDF pages", text.length(), pageCount);
            return new PdfExtractionResult(text, pageCount);
        }
    }

    /**
     * {@link OcrPort} implementation — extracts text from PDF bytes.
     *
     * @param fileBytes raw PDF bytes
     * @param mimeType  expected to be {@code application/pdf}
     * @return extracted plain text
     */
    @Override
    public String extractText(byte[] fileBytes, String mimeType) {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            log.debug("OcrPort extraction: {} chars from PDF", text.length());
            return text;
        } catch (IOException e) {
            log.error("PDF text extraction failed", e);
            throw new RuntimeException("PDF text extraction failed", e);
        }
    }
}
