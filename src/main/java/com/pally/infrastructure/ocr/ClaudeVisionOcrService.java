package com.pally.infrastructure.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.port.OcrPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * OCR via Claude Haiku vision — no native binaries, works on Railway out
 * of the box. Replaces {@link TesseractOcrService} (which was a stub) for
 * every {@link OcrPort} injection point because of {@link Primary}.
 *
 * <p>Cost: ~$0.002 per image (Haiku vision, ~1K input tokens). Falls back
 * to an empty string on any failure so the upload flow can carry on with
 * its "no readable text" handling instead of crashing.
 */
@Component
@Primary
public class ClaudeVisionOcrService implements OcrPort {

    private static final Logger log =
            LoggerFactory.getLogger(ClaudeVisionOcrService.class);

    @Value("${claude.api.key}")
    private String apiKey;

    @Value("${claude.api.model}")
    private String model;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    /// Legacy entry point kept for {@code UploadFileUseCase} which still
    /// calls the InputStream overload directly.
    public String extractText(InputStream inputStream) {
        try {
            return extractText(inputStream.readAllBytes(), "image/jpeg");
        } catch (IOException e) {
            log.error("[VisionOCR] Failed to read stream", e);
            return "";
        }
    }

    @Override
    public String extractText(byte[] fileBytes, String mimeType) {
        if (fileBytes == null || fileBytes.length == 0) return "";
        try {
            String b64 = Base64.getEncoder().encodeToString(fileBytes);
            String mime = normaliseMime(mimeType);

            String body = mapper.writeValueAsString(Map.of(
                    "model", model,
                    "max_tokens", 2048,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", List.of(
                                    Map.of(
                                            "type", "image",
                                            "source", Map.of(
                                                    "type", "base64",
                                                    "media_type", mime,
                                                    "data", b64
                                            )
                                    ),
                                    Map.of(
                                            "type", "text",
                                            "text", """
                                                    Extract ALL text from this image exactly as written.
                                                    Include every word, number, equation, and symbol.
                                                    Preserve structure: headings, bullet points, numbered lists.
                                                    Output ONLY the extracted text — no commentary.
                                                    If no readable text, output exactly: (no text found)
                                                    """
                                    )
                            )
                    ))
            ));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.anthropic.com/v1/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res =
                    http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.error("[VisionOCR] API error {}: {}",
                        res.statusCode(),
                        res.body() == null
                                ? ""
                                : res.body().substring(
                                        0, Math.min(300, res.body().length())));
                return "";
            }

            JsonNode root = mapper.readTree(res.body());
            JsonNode content = root.path("content");
            if (!content.isArray() || content.isEmpty()) return "";
            String text = content.get(0).path("text").asText("").strip();
            if ("(no text found)".equalsIgnoreCase(text)) text = "";
            log.info("[VisionOCR] Extracted {} chars (mime={})",
                    text.length(), mime);
            return text;
        } catch (Exception e) {
            log.error("[VisionOCR] Vision OCR failed", e);
            return "";
        }
    }

    private String normaliseMime(String mimeType) {
        if (mimeType == null) return "image/jpeg";
        return switch (mimeType.toLowerCase()) {
            case "image/jpg", "image/jpeg" -> "image/jpeg";
            case "image/png"  -> "image/png";
            case "image/gif"  -> "image/gif";
            case "image/webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }
}
