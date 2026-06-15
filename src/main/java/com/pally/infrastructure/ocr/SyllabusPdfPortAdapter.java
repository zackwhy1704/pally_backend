package com.pally.infrastructure.ocr;

import com.pally.domain.curriculum.port.SyllabusPdfPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * Infrastructure adapter implementing {@link SyllabusPdfPort} by
 * delegating to {@link PdfTextExtractor}.
 */
@Component
@RequiredArgsConstructor
public class SyllabusPdfPortAdapter implements SyllabusPdfPort {

    private final PdfTextExtractor pdfTextExtractor;

    @Override
    public String extractText(InputStream pdfStream) throws Exception {
        return pdfTextExtractor.extract(pdfStream).text();
    }
}
