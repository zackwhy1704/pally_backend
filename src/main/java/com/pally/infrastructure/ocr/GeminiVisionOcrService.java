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
import java.util.ArrayList;
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

    /**
     * The vision model used for OCR. gemini-1.5-* was retired by Google (404s);
     * this is the current multimodal flash. Kept as a constant so the live
     * smoke probe ({@link #probe}) tests the exact model production uses.
     */
    static final String VISION_MODEL = "gemini-2.5-flash";

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failCount = new AtomicLong();

    private final com.pally.domain.cost.AiUsageMeter aiUsageMeter;

    public GeminiVisionOcrService(com.pally.domain.cost.AiUsageMeter aiUsageMeter) {
        this.aiUsageMeter = aiUsageMeter;
    }

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
                    + "/v1beta/models/" + VISION_MODEL + ":generateContent?key=" + apiKey;

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

            // Cost ledger (was a blind spot): meter the OCR call. Gemini returns
            // usageMetadata in this body; if absent, byte/char-estimate flagged.
            JsonNode usage = root.path("usageMetadata");
            boolean measured = !usage.isMissingNode();
            aiUsageMeter.record(null, null, com.pally.domain.cost.AiCallType.OTHER, "ocr",
                    com.pally.domain.cost.AiTrigger.COMPILE, VISION_MODEL,
                    measured ? usage.path("promptTokenCount").asLong(0) : fileBytes.length / 750L,
                    measured ? usage.path("candidatesTokenCount").asLong(0) : extracted.length() / 4L,
                    true, !measured);
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

    /** The vision/OCR model this service calls. */
    public String visionModel() {
        return VISION_MODEL;
    }

    /**
     * Result of a live {@link #probe}: the raw HTTP status from the Gemini API.
     *
     * @param model       the model id that was probed
     * @param kind        "text" or "vision"
     * @param statusCode  HTTP status (200 = model resolves and answered; 404 =
     *                    model not found/retired for this key; -1 = transport error)
     * @param ok          true iff statusCode == 200
     * @param elapsedMs   round-trip time
     * @param bodySnippet first 300 chars of the response/error body
     */
    public record ProbeResult(String model, String kind, int statusCode,
                              boolean ok, long elapsedMs, String bodySnippet) {}

    /**
     * Live diagnostic: sends a minimal request to {@code model} using the real
     * configured API key and returns the raw HTTP status — without swallowing
     * errors the way {@link #extractText} does. Used by the admin smoke endpoint
     * to prove, against the production key, whether a model resolves (200) or is
     * retired/unavailable (404) for both the text and vision/OCR code paths.
     *
     * @param model      the model id to probe (e.g. {@value #VISION_MODEL})
     * @param imageBytes when non-null, sent as inline image data (exercises the
     *                   exact multimodal OCR path); when null, a text-only ping
     */
    public ProbeResult probe(String model, byte[] imageBytes) {
        String kind = (imageBytes != null && imageBytes.length > 0) ? "vision" : "text";
        if (apiKey == null || apiKey.isBlank()) {
            return new ProbeResult(model, kind, 0, false, 0, "no api key configured");
        }
        try {
            List<Object> parts = new ArrayList<>();
            if ("vision".equals(kind)) {
                parts.add(Map.of("inline_data", Map.of(
                        "mime_type", "image/png",
                        "data", Base64.getEncoder().encodeToString(imageBytes))));
                parts.add(Map.of("text", EXTRACTION_PROMPT));
            } else {
                parts.add(Map.of("text", "ping"));
            }
            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of("parts", parts)),
                    "generationConfig", Map.of("maxOutputTokens", 16, "temperature", 0));

            String url = baseUrl + "/v1beta/models/" + model + ":generateContent?key=" + apiKey;
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            long start = System.currentTimeMillis();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            long elapsed = System.currentTimeMillis() - start;
            log.info("[GeminiProbe] model={} kind={} status={} ({}ms)",
                    model, kind, res.statusCode(), elapsed);
            return new ProbeResult(model, kind, res.statusCode(),
                    res.statusCode() == 200, elapsed, truncate(res.body(), 300));
        } catch (Exception e) {
            log.warn("[GeminiProbe] model={} kind={} transport error: {}", model, kind, e.toString());
            return new ProbeResult(model, kind, -1, false, 0,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
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
