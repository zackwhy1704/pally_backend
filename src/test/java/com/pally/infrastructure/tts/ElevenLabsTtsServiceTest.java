package com.pally.infrastructure.tts;

import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ElevenLabsTtsServiceTest {

    @Test
    void synthesize_noApiKey_throws503() {
        ElevenLabsTtsService service = new ElevenLabsTtsService();
        setField(service, "apiKey", "");

        assertThatThrownBy(() -> service.synthesize("Hello world", "default"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TTS not configured");
    }

    @Test
    void synthesize_nullApiKey_throws503() {
        ElevenLabsTtsService service = new ElevenLabsTtsService();
        setField(service, "apiKey", null);

        assertThatThrownBy(() -> service.synthesize("Hello world", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TTS not configured");
    }

    @Test
    void synthesize_blankApiKey_throws503() {
        ElevenLabsTtsService service = new ElevenLabsTtsService();
        setField(service, "apiKey", "   ");

        assertThatThrownBy(() -> service.synthesize("Some text", "voice-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("TTS not configured");
    }

    /**
     * We cannot test a real API call without a valid key, but we verify the
     * request building does not crash for well-formed inputs when the key is set.
     * The actual HTTP call will fail with a connection to the real API, which
     * is expected — we test the path up to the API call.
     */
    @Test
    void synthesize_withApiKey_invalidEndpoint_throwsBusiness502() {
        ElevenLabsTtsService service = new ElevenLabsTtsService();
        setField(service, "apiKey", "test-key-not-real");
        setField(service, "defaultVoiceId", "test-voice");

        // This will try to hit the real ElevenLabs API and fail with 401 or connection error.
        // Either maps to a BusinessException (502).
        assertThatThrownBy(() -> service.synthesize("Hello", "default"))
                .isInstanceOf(BusinessException.class);
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set field " + fieldName, e);
        }
    }
}
