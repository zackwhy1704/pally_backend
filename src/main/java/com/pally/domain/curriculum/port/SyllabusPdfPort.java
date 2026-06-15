package com.pally.domain.curriculum.port;

import java.io.InputStream;

/**
 * Domain port for extracting raw text from a syllabus PDF.
 * Implemented in {@code infrastructure/ocr}; never import JPA or HTTP here.
 */
public interface SyllabusPdfPort {

    /**
     * Extracts plain text from a PDF input stream.
     *
     * @throws RuntimeException if extraction fails
     */
    String extractText(InputStream pdfStream) throws Exception;
}
