package com.pally.infrastructure.tts;

import com.pally.domain.module.port.TtsPort;
import com.pally.shared.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * ElevenLabs text-to-speech implementation.
 *
 * <p>Uses the v1 text-to-speech endpoint to synthesize MP3 audio.
 * Follows the same {@code java.net.http.HttpClient} pattern as
 * {@link com.pally.infrastructure.ocr.ClaudeVisionOcrService}.
 */
@Component
@Slf4j
public class ElevenLabsTtsService implements TtsPort {

    private static final String BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech/";

    @Value("${elevenlabs.api.key:}")
    private String apiKey;

    @Value("${elevenlabs.voice.id:21m00Tcm4TlvDq8ikWAM}")
    private String defaultVoiceId;

    private final HttpClient http = HttpClient.newHttpClient();

    @Override
    public byte[] synthesize(String text, String voiceId) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BusinessException("TTS not configured — set ELEVENLABS_API_KEY", 503);
        }

        String vid = (voiceId != null && !voiceId.equals("default")) ? voiceId : defaultVoiceId;
        String callId = java.util.UUID.randomUUID().toString().substring(0, 8);

        log.info("[TTS-{}] REQUEST voiceId={} textChars={}", callId, vid, text.length());
        long start = System.currentTimeMillis();

        String body = """
                {"text": %s, "model_id": "eleven_monolingual_v1", \
                "voice_settings": {"stability": 0.5, "similarity_boost": 0.75}}"""
                .formatted(escapeJson(text));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + vid))
                .header("xi-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "audio/mpeg")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long ms = System.currentTimeMillis() - start;

            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body()).substring(0, Math.min(500, response.body().length));
                log.error("[TTS-{}] FAILED status={} after {}ms: {}", callId, response.statusCode(), ms, errorBody);
                throw new BusinessException(
                        "TTS synthesis failed (HTTP " + response.statusCode() + ")", 502);
            }

            log.info("[TTS-{}] RESPONSE {}ms audioBytes={}", callId, ms, response.body().length);
            return response.body();

        } catch (BusinessException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("[TTS-{}] Connection error: {}", callId, e.getMessage());
            throw new BusinessException("TTS synthesis failed — connection error", 502);
        }
    }

    /**
     * Escapes a string for embedding in a JSON value.
     */
    private String escapeJson(String text) {
        return "\"" + text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
