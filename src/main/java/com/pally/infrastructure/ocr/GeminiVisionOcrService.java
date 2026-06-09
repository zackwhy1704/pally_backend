package com.pally.infrastructure.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.port.OcrPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OCR via Gemini Flash vision — no native deps, fallback when Claude OCR
 * fails or is rate-limited. Uses the same text extraction prompt as
 * {@link ClaudeVisionOcrService}.
 *
 * <p>Cost: free tier (1,500 req/day) or ~$0.001 per image on paid tier.
 */
@Component
public class GeminiVisionOcrService implements OcrPort {

    private static final Logger log =
            LoggerFactory.getLogger(GeminiVisionOcrService.class);

    private static final String EXTRACTION_PROMPT = """
            Extract ALL text from this image exactly as written.
            Include every word, number, equation, and symbol.
            Preserve structure: headings, bullet points, numbered lists.
            Output ONLY the extracted text — no commentary.
            If no readable text, output exactly: (no text found)
            """;

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();

    @Override
    public String extractText(byte[] fileBytes, String mimeType) {
        if (fileBytes == null || fileBytes.length == 0) return "";
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[GeminiOCR] No API key configured — skipping");
            return "";
        }

        try {
            String b64 = Base64.getEncoder().encodeToString(fileBytes);
            String mime = normaliseMime(mimeType);

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(
                                    Map.of("inline_data", Map.of(
                                            "mime_type", mime,
                                            "data", b64
                                    )),
                                    Map.of("text", EXTRACTION_PROMPT)
                            )
                    )),
                    "generationConfig", Map.of(
                            "maxOutputTokens", 4096,
                            "temperature", 0.1
                    )
            );

            String url = baseUrl
                    + "/v1beta/models/gemini-1.5-flash-latest:generateContent?key=" + apiKey;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;

            if (res.statusCode() != 200) {
                failCount.incrementAndGet();
                log.error("[GeminiOCR] API error {} ({}ms, totalFails={}): {}",
                        res.statusCode(), elapsed, failCount.get(),
                        truncate(res.body(), 300));
                return "";
            }

            JsonNode root = mapper.readTree(res.body());
            JsonNode text = root
                    .path("candidates").path(0)
                    .path("content").path("parts").path(0)
                    .path("text");

            if (text.isMissingNode()) {
                failCount.incrementAndGet();
                log.warn("[GeminiOCR] Empty text response ({}ms)", elapsed);
                return "";
            }

            String extracted = text.asText("").strip();
            if ("(no text found)".equalsIgnoreCase(extracted)) extracted = "";

            long successes = successCount.incrementAndGet();
            log.info("[GeminiOCR] Extracted {} chars ({}ms, mime={}, totalOk={})",
                    extracted.length(), elapsed, mime, successes);
            return extracted;

        } catch (Exception e) {
            failCount.incrementAndGet();
            log.error("[GeminiOCR] Vision OCR failed (totalFails={})",
                    failCount.get(), e);
            return "";
        }
    }

    /**
     * @return true if a Gemini API key is configured.
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    private String normaliseMime(String mimeType) {
        if (mimeType == null) return "image/jpeg";
        return switch (mimeType.toLowerCase()) {
            case "image/jpg", "image/jpeg" -> "image/jpeg";
            case "image/png" -> "image/png";
            case "image/gif" -> "image/gif";
            case "image/webp" -> "image/webp";
            default -> "image/jpeg";
        };
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
