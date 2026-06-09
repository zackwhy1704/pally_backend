package com.pally.infrastructure.ocr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for GeminiVisionOcrService. Since we can't call the real API,
 * tests focus on edge cases and the isAvailable() guard.
 */
class GeminiVisionOcrServiceTest {

    private GeminiVisionOcrService service;

    @BeforeEach
    void setUp() {
        service = new GeminiVisionOcrService();
    }

    @Test
    void extractText_nullBytes_returnsEmpty() {
        assertThat(service.extractText(null, "image/jpeg")).isEmpty();
    }

    @Test
    void extractText_emptyBytes_returnsEmpty() {
        assertThat(service.extractText(new byte[0], "image/png")).isEmpty();
    }

    @Test
    void extractText_noApiKey_returnsEmpty() {
        // Default @Value is empty string, so no API key
        ReflectionTestUtils.setField(service, "apiKey", "");
        assertThat(service.extractText(new byte[]{1, 2, 3}, "image/jpeg")).isEmpty();
    }

    @Test
    void extractText_nullApiKey_returnsEmpty() {
        ReflectionTestUtils.setField(service, "apiKey", null);
        assertThat(service.extractText(new byte[]{1, 2, 3}, "image/jpeg")).isEmpty();
    }

    @Test
    void isAvailable_noKey_returnsFalse() {
        ReflectionTestUtils.setField(service, "apiKey", "");
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void isAvailable_withKey_returnsTrue() {
        ReflectionTestUtils.setField(service, "apiKey", "test-key-123");
        assertThat(service.isAvailable()).isTrue();
    }
}
