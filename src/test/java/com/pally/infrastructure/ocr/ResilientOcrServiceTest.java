package com.pally.infrastructure.ocr;

import com.pally.shared.exception.OcrUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for the OCR fallback chain logic in ResilientOcrService.
 * Verifies: Claude → Gemini cascade, quality guard, servedBy/degraded metadata,
 * and OcrUnavailableException when both engines fail.
 */
@ExtendWith(MockitoExtension.class)
class ResilientOcrServiceTest {

    @Mock ClaudeVisionOcrService claudeOcr;
    @Mock GeminiVisionOcrService geminiOcr;

    private ResilientOcrService service;

    @BeforeEach
    void setUp() {
        service = new ResilientOcrService(claudeOcr, geminiOcr);
    }

    @Test
    void extractText_claudeSucceeds_returnsClaudeResult() {
        when(claudeOcr.extractText(any(), eq("image/jpeg")))
                .thenReturn("Hello world from Claude OCR");

        String result = service.extractText(new byte[100], "image/jpeg");

        assertThat(result).isEqualTo("Hello world from Claude OCR");
        OcrResult meta = service.getLastResult();
        assertThat(meta.servedBy()).isEqualTo("claude-vision");
        assertThat(meta.degraded()).isFalse();
        verify(geminiOcr, never()).extractText(any(), any());
    }

    @Test
    void extractText_claudeFails_geminiSucceeds_returnsDegraded() {
        when(claudeOcr.extractText(any(), any()))
                .thenThrow(new RuntimeException("Claude down"));
        when(geminiOcr.isAvailable()).thenReturn(true);
        when(geminiOcr.extractText(any(), eq("image/png")))
                .thenReturn("Gemini extracted text here");

        String result = service.extractText(new byte[100], "image/png");

        assertThat(result).isEqualTo("Gemini extracted text here");
        OcrResult meta = service.getLastResult();
        assertThat(meta.servedBy()).isEqualTo("gemini-vision");
        assertThat(meta.degraded()).isTrue();
    }

    @Test
    void extractText_claudeAndGeminiBothReturnEmpty_throwsOcrUnavailable() {
        when(claudeOcr.extractText(any(), any())).thenReturn("");
        when(geminiOcr.isAvailable()).thenReturn(true);
        when(geminiOcr.extractText(any(), any())).thenReturn("");

        assertThatThrownBy(() -> service.extractText(new byte[100], "image/jpeg"))
                .isInstanceOf(OcrUnavailableException.class);

        assertThat(service.getLastResult().servedBy()).isEqualTo("all-failed");
    }

    @Test
    void extractText_claudeAndGeminiBothThrow_throwsOcrUnavailable() {
        when(claudeOcr.extractText(any(), any()))
                .thenThrow(new RuntimeException("Claude timeout"));
        when(geminiOcr.isAvailable()).thenReturn(true);
        when(geminiOcr.extractText(any(), any()))
                .thenThrow(new RuntimeException("Gemini timeout"));

        assertThatThrownBy(() -> service.extractText(new byte[100], "image/jpeg"))
                .isInstanceOf(OcrUnavailableException.class)
                .hasMessageContaining("Gemini timeout");
    }

    @Test
    void extractText_qualityGuard_claudeReturnsTooShort_triesGemini() {
        // Large image (>10KB) but Claude only extracts 5 chars
        byte[] largeImage = new byte[20_000];
        when(claudeOcr.extractText(any(), any())).thenReturn("abcde");
        when(geminiOcr.isAvailable()).thenReturn(true);
        when(geminiOcr.extractText(any(), any()))
                .thenReturn("A much longer and proper extraction from Gemini");

        String result = service.extractText(largeImage, "image/jpeg");

        assertThat(result).isEqualTo("A much longer and proper extraction from Gemini");
        assertThat(service.getLastResult().servedBy()).isEqualTo("gemini-vision");
    }

    @Test
    void extractText_smallImage_shortTextAccepted() {
        // Small image (<10KB) — short text is OK, no quality threshold applies
        byte[] smallImage = new byte[5_000];
        when(claudeOcr.extractText(any(), any())).thenReturn("A=5");

        String result = service.extractText(smallImage, "image/jpeg");

        assertThat(result).isEqualTo("A=5");
        assertThat(service.getLastResult().servedBy()).isEqualTo("claude-vision");
        assertThat(service.getLastResult().degraded()).isFalse();
    }

    @Test
    void extractText_geminiNotAvailable_claudeFails_throwsOcrUnavailable() {
        when(claudeOcr.extractText(any(), any())).thenReturn("");
        when(geminiOcr.isAvailable()).thenReturn(false);

        assertThatThrownBy(() -> service.extractText(new byte[100], "image/jpeg"))
                .isInstanceOf(OcrUnavailableException.class)
                .hasMessageContaining("Gemini is not configured");

        verify(geminiOcr, never()).extractText(any(byte[].class), any());
    }

    @Test
    void extractText_nullBytes_returnsEmpty() {
        String result = service.extractText(null, "image/jpeg");

        assertThat(result).isEmpty();
        assertThat(service.getLastResult().servedBy()).isEqualTo("none");
    }

    @Test
    void extractText_emptyBytes_returnsEmpty() {
        String result = service.extractText(new byte[0], "image/jpeg");

        assertThat(result).isEmpty();
        assertThat(service.getLastResult().servedBy()).isEqualTo("none");
    }
}
