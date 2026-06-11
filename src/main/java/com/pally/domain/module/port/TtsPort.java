package com.pally.domain.module.port;

/**
 * Port for text-to-speech synthesis.
 * Returns raw audio bytes (MP3) for the given text.
 */
public interface TtsPort {

    /**
     * Synthesizes the given text into audio.
     *
     * @param text    the text to synthesize
     * @param voiceId the voice identifier (implementation-specific)
     * @return MP3 audio bytes
     */
    byte[] synthesize(String text, String voiceId);
}
