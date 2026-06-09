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
import java.util.concurrent.atomic.AtomicLong;

/**
 * OCR via Claude Haiku vision — no native binaries, works on Railway out
 * of the box. Replaces {@link TesseractOcrService} (which was a stub) for
 * every {@link OcrPort} injection point because of {@link Primary}.
 *
 * <p>Retries 429 (rate-limit) and 5xx (server error) up to 3 times with
 * exponential backoff (2s → 4s → 8s). This is the single point of failure
 * for every image upload — without retry, a brief Anthropic rate-limit
 * spike kills all image ingestion.
 *
 * <p>Cost: ~$0.002 per image (Haiku vision, ~1K input tokens).
 */
@Component
@Primary
public class ClaudeVisionOcrService implements OcrPort {

    private static final Logger log =
            LoggerFactory.getLogger(ClaudeVisionOcrService.class);

    private static final int MAX_RETRIES = 3;
    private static final long BASE_BACKOFF_MS = 2_000;

    @Value("${claude.api.key}")
    private String apiKey;

    @Value("${claude.api.model}")
    private String model;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    // Observable counters — log.warn when retries fire so Railway logs surface it.
    private final AtomicLong retryCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();

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

            HttpResponse<String> res = sendWithRetry(req);
            if (res.statusCode() != 200) {
                failCount.incrementAndGet();
                log.error("[VisionOCR] API error {} after retries (total fails={}): {}",
                        res.statusCode(), failCount.get(),
                        truncate(res.body(), 300));
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
            failCount.incrementAndGet();
            log.error("[VisionOCR] Vision OCR failed (total fails={})", failCount.get(), e);
            return "";
        }
    }

    /**
     * Retries on 429 (rate-limit) and 5xx (server error) with exponential
     * backoff. Non-retryable status codes (400, 401, etc.) return immediately.
     */
    private HttpResponse<String> sendWithRetry(HttpRequest req) throws Exception {
        HttpResponse<String> res = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = res.statusCode();
            boolean retryable = status == 429 || status >= 500;
            if (!retryable || attempt == MAX_RETRIES) return res;

            long retries = retryCount.incrementAndGet();
            long backoff = BASE_BACKOFF_MS * (1L << attempt); // 2s, 4s, 8s
            // Parse retry-after header if present (Anthropic sends it on 429)
            String retryAfter = res.headers().firstValue("retry-after").orElse(null);
            if (retryAfter != null) {
                try {
                    long hinted = (long) (Double.parseDouble(retryAfter) * 1000);
                    if (hinted > 0 && hinted < 30_000) backoff = hinted;
                } catch (NumberFormatException ignored) {}
            }
            log.warn("[VisionOCR] HTTP {} — retry {}/{} after {}ms (total retries={})",
                    status, attempt + 1, MAX_RETRIES, backoff, retries);
            Thread.sleep(backoff);
        }
        return res;
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
