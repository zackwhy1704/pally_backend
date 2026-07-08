package com.pally.infrastructure.ocr;

import com.pally.domain.knowledge.port.OcrPort;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
     * Extracts all text from a PDF byte array.
     *
     * <p>Preferred over {@link #extract(InputStream)} when the caller already
     * holds the bytes in memory — avoids the risk of reading the same
     * {@link java.io.InputStream} twice (the {@code MultipartFile} stream is
     * drained on first read and returns EOF on subsequent calls).
     *
     * @param bytes raw PDF bytes
     * @return {@link PdfExtractionResult} with extracted text and page count
     * @throws IOException if the PDF cannot be read or parsed
     */
    public PdfExtractionResult extractFromBytes(byte[] bytes) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            int pageCount = document.getNumberOfPages();
            log.debug("Extracted {} chars from {} PDF pages", text.length(), pageCount);
            return new PdfExtractionResult(text, pageCount);
        }
    }

    /**
     * Extracts all text from a PDF input stream.
     *
     * @param inputStream PDF content (caller is responsible for closing)
     * @return {@link PdfExtractionResult} with extracted text and page count
     * @throws IOException if the PDF cannot be read or parsed
     */
    public PdfExtractionResult extract(InputStream inputStream) throws IOException {
        return extractFromBytes(inputStream.readAllBytes());
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

    // ── Chapter-chunking support ────────────────────────────────────────────

    /** A PDF bookmark/outline entry: a chapter title + its 1-based start page. */
    public record Bookmark(String title, int startPage) {}

    /**
     * Top-level outline entries (chapters) with their start pages, in document
     * order. Empty when the PDF has no outline. Best-effort: a bookmark whose
     * destination can't be resolved is skipped, never thrown — segmentation must
     * degrade to LLM/range fallback, never fail an upload.
     */
    public List<Bookmark> extractOutline(byte[] bytes) {
        List<Bookmark> marks = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
            if (outline == null) return List.of();
            for (PDOutlineItem item : outline.children()) {
                try {
                    PDPage page = item.findDestinationPage(document);
                    if (page == null) continue;
                    int idx = document.getPages().indexOf(page); // 0-based
                    String title = item.getTitle();
                    if (idx < 0 || title == null || title.isBlank()) continue;
                    marks.add(new Bookmark(title.trim(), idx + 1));
                } catch (IOException e) {
                    // unresolvable bookmark — skip, keep the rest
                }
            }
        } catch (IOException e) {
            log.warn("[Segment] outline extraction failed, will fall back: {}", e.getMessage());
            return List.of();
        }
        return marks;
    }

    /** Extract text for a 1-based inclusive page range [startPage, endPage]. */
    public String extractPageRange(byte[] bytes, int startPage, int endPage) throws IOException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(startPage);
            stripper.setEndPage(endPage);
            return stripper.getText(document);
        }
    }

    /**
     * Per-page text for pages 1..min(pageCount, maxPages), loading the PDF once.
     * Used to build a page-marked sample for the LLM segmentation fallback so it
     * can return chapter boundaries as page numbers.
     */
    public List<String> extractPerPage(byte[] bytes, int maxPages) throws IOException {
        List<String> pages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int total = Math.min(document.getNumberOfPages(), maxPages);
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= total; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                pages.add(stripper.getText(document));
            }
        }
        return pages;
    }
}
