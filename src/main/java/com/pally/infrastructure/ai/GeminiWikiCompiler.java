package com.pally.infrastructure.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.knowledge.KnowledgeFile;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.port.WikiCompilerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.pally.domain.avatar.Subject;
import com.pally.domain.knowledge.port.StoragePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Gemini 2.0 Flash implementation of {@link WikiCompilerPort}.
 *
 * <p>Marked {@code @Primary} so it replaces {@link ClaudeWikiCompiler} for wiki
 * compilation. Claude remains the implementation for everything else (chat, quiz,
 * relevance). Falls back to Claude automatically when the Gemini key is missing
 * or when a Gemini call fails, so a misconfigured key degrades gracefully.
 *
 * <p>Why Gemini Flash for wiki compile:
 * <ul>
 *   <li>Input cost: $0.075/M tokens vs Haiku $0.80/M — 10× cheaper</li>
 *   <li>1M token context window — an entire textbook in one call, no chunking</li>
 *   <li>Better synthesis quality than Haiku for structured knowledge extraction</li>
 *   <li>Free tier: 1,500 requests/day — enough for hundreds of uploads/day</li>
 * </ul>
 */
@Primary
@Component
public class GeminiWikiCompiler implements WikiCompilerPort {

    private static final Logger log = LoggerFactory.getLogger(GeminiWikiCompiler.class);

    @Value("${gemini.api.key:}")
    private String apiKey;

    // Fallback chain: gemini-2.5-flash → gemini-2.5-flash-lite → Claude Haiku.
    // gemini-1.5-* AND gemini-2.0-flash are retired by Google (confirmed 404
    // "no longer available" in prod) — defaults must stay on the 2.5 family.
    // Override either via GEMINI_MODEL_PRIMARY / GEMINI_MODEL_SECONDARY.
    @Value("${gemini.api.model.primary:gemini-2.5-flash}")
    private String modelPrimary;

    @Value("${gemini.api.model.secondary:gemini-2.5-flash-lite}")
    private String modelSecondary;

    @Value("${gemini.api.base-url:https://generativelanguage.googleapis.com}")
    private String baseUrl;

    @Value("${compile.haiku-fallback.enabled:true}")
    private boolean haikuFallbackEnabled;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ClaudeWikiCompiler claudeFallback;
    private final StoragePort storagePort;
    private final com.pally.domain.cost.AiUsageMeter aiUsageMeter;
    private final GeminiThinkingBudgetConfig thinkingConfig;

    // Observable counters — exposed via logs so Railway can be dashboarded.
    private final AtomicLong tier1Count = new AtomicLong();
    private final AtomicLong tier2Count = new AtomicLong();
    private final AtomicLong haikuFallbackCount = new AtomicLong();

    // Max images to include in a single STEM compile call to avoid token bloat.
    // Each image costs ~260 tokens; 10 images ≈ 2,600 tokens — well within 1M.
    static final int MAX_STEM_IMAGES = 10;

    // ── Quota counter + cooldown ────────────────────────────────────────────
    private static final int QUOTA_WARN_THRESHOLD = 1200;
    private static final long COOLDOWN_DURATION_MS = 5 * 60 * 1000L; // 5 minutes
    private static final ZoneId QUOTA_TIMEZONE = ZoneId.of("Asia/Singapore");

    private final AtomicInteger dailyRequestCount = new AtomicInteger(0);
    private volatile LocalDate quotaResetDate = LocalDate.now(QUOTA_TIMEZONE);
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>(Instant.EPOCH);

    public GeminiWikiCompiler(
            WebClient webClient,
            ObjectMapper objectMapper,
            ClaudeWikiCompiler claudeFallback,
            StoragePort storagePort,
            com.pally.domain.cost.AiUsageMeter aiUsageMeter,
            GeminiThinkingBudgetConfig thinkingConfig) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.claudeFallback = claudeFallback;
        this.storagePort = storagePort;
        this.aiUsageMeter = aiUsageMeter;
        this.thinkingConfig = thinkingConfig;
    }

    /** Attribute a compile call by the avatar it's for. */
    private static com.pally.domain.cost.AiCallType callTypeFor(Avatar avatar) {
        if (avatar == null) return com.pally.domain.cost.AiCallType.COMPILE;
        return switch (avatar.getKind()) {
            case WEAKNESS_PROFILE -> com.pally.domain.cost.AiCallType.WEAKNESS_REBUILD;
            case MARKING_CORPUS   -> com.pally.domain.cost.AiCallType.MARKING;
            default               -> com.pally.domain.cost.AiCallType.COMPILE;
        };
    }

    // ── package-private for testing ─────────────────────────────────────────
    int getDailyRequestCount() { return dailyRequestCount.get(); }
    Instant getCooldownUntil() { return cooldownUntil.get(); }
    void setCooldownUntil(Instant until) { cooldownUntil.set(until); }
    void resetDailyCounter() { dailyRequestCount.set(0); quotaResetDate = LocalDate.now(QUOTA_TIMEZONE); }

    @jakarta.annotation.PostConstruct
    void logConfig() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Gemini] ⚠️  GEMINI_API_KEY is NOT SET — compile will use Claude Haiku (chunked)");
        } else {
            log.info("[Gemini] ✅ Compiler chain: {} → {} → Claude Haiku (chunked) | key={}…{}",
                    modelPrimary, modelSecondary,
                    apiKey.substring(0, Math.min(8, apiKey.length())),
                    apiKey.length() > 8 ? apiKey.substring(apiKey.length() - 4) : "");
        }
    }

    @Override
    public List<WikiPageDraft> compile(Avatar avatar, List<KnowledgeFile> files,
                                       List<WikiPage> existingPages) {
        return compileWithTier(avatar, files, existingPages).drafts();
    }

    @Override
    public CompileOutput compileWithTier(Avatar avatar, List<KnowledgeFile> files,
                                          List<WikiPage> existingPages) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("[Gemini] No API key — using Claude Haiku with chunked compile");
            haikuFallbackCount.incrementAndGet();
            return fallbackToHaiku(avatar, files, existingPages, "no Gemini key");
        }

        // ── Cooldown check: skip Gemini entirely if we hit a 429 recently ───
        if (isInCooldown()) {
            log.warn("[Gemini] In cooldown until {} — skipping Gemini",
                    cooldownUntil.get());
            return fallbackToHaiku(avatar, files, existingPages, "Gemini cooldown active");
        }

        int totalChars = files.stream()
                .mapToInt(f -> f.getExtractedText() != null ? f.getExtractedText().length() : 0)
                .sum();
        files.forEach(f -> log.info("[Gemini]   file: {} status={} chars={}",
                f.getFileName(), f.getStatus(),
                f.getExtractedText() != null ? f.getExtractedText().length() : 0));

        // ── Tier 1: Gemini 1.5 Flash (primary — fast, free tier) ─────────
        log.info("[Gemini] ──► Tier 1 ({}) avatarId={} files={} totalChars={}",
                modelPrimary, avatar.getId(), files.size(), totalChars);
        try {
            List<WikiPageDraft> drafts = compileWithModel(modelPrimary, avatar, files, existingPages, totalChars);
            if (!drafts.isEmpty()) {
                long count = tier1Count.incrementAndGet();
                log.info("[Gemini] ◄── Tier 1 SUCCESS: {} pages (tier1Total={})", drafts.size(), count);
                return new CompileOutput(drafts, "gemini-tier1-" + modelPrimary);
            }
            log.warn("[Gemini] Tier 1 returned 0 pages — trying Tier 2");
        } catch (Exception e) {
            log.warn("[Gemini] Tier 1 FAILED ({}): {} — trying Tier 2 ({})",
                    e.getClass().getSimpleName(), e.getMessage(), modelSecondary);
        }

        // ── Tier 2: Gemini 2.0 Flash (secondary — paid, better reasoning) ─
        log.info("[Gemini] ──► Tier 2 ({}) avatarId={}", modelSecondary, avatar.getId());
        try {
            List<WikiPageDraft> drafts = compileWithModel(modelSecondary, avatar, files, existingPages, totalChars);
            if (!drafts.isEmpty()) {
                long count = tier2Count.incrementAndGet();
                log.info("[Gemini] ◄── Tier 2 SUCCESS: {} pages (tier2Total={})", drafts.size(), count);
                return new CompileOutput(drafts, "gemini-tier2-" + modelSecondary);
            }
            log.warn("[Gemini] Tier 2 returned 0 pages — falling back to Claude Haiku (chunked)");
        } catch (Exception e) {
            log.warn("[Gemini] Tier 2 FAILED ({}): {} — falling back to Claude Haiku (chunked)",
                    e.getClass().getSimpleName(), e.getMessage());
        }

        // ── Tier 3: Claude Haiku (with chunked compilation for large files) ─
        return fallbackToHaiku(avatar, files, existingPages,
                "Tier 1+2 failed for avatarId=" + avatar.getId() + " totalChars=" + totalChars);
    }

    private CompileOutput fallbackToHaiku(Avatar avatar, List<KnowledgeFile> files,
                                           List<WikiPage> existingPages, String reason) {
        if (!haikuFallbackEnabled) {
            throw new RuntimeException(
                    "Gemini compile failed and Haiku fallback is disabled. Reason: " + reason);
        }
        long fallbacks = haikuFallbackCount.incrementAndGet();
        log.warn("[Gemini] HAIKU FALLBACK (total={}): {} — this costs ~10x more than Gemini.",
                fallbacks, reason);
        log.info("[Gemini] ──► Tier 3 (Claude Haiku + chunked) avatarId={}", avatar.getId());
        List<WikiPageDraft> haikuDrafts = claudeFallback.compile(avatar, files, existingPages);
        log.info("[Gemini] ◄── Tier 3 result: {} pages", haikuDrafts.size());
        return new CompileOutput(haikuDrafts, "haiku-chunked-fallback");
    }

    /**
     * Check if we're in a cooldown period after a 429 response.
     */
    boolean isInCooldown() {
        return Instant.now().isBefore(cooldownUntil.get());
    }

    private List<WikiPageDraft> compileWithModel(String modelName, Avatar avatar,
                                                  List<KnowledgeFile> files,
                                                  List<WikiPage> existingPages,
                                                  int totalChars) {
        long start = System.currentTimeMillis();

        // For STEM subjects, collect original images to send alongside text
        List<ImageData> stemImages = collectStemImages(avatar, files);

        List<WikiPageDraft> drafts;
        if (totalChars > 30_000) {
            drafts = compileChunked(modelName, avatar, files, existingPages);
        } else {
            String prompt = buildPrompt(avatar, files, existingPages);
            log.debug("[Gemini] {} prompt: {} chars (~{} tokens) stemImages={}",
                    modelName, prompt.length(), prompt.length() / 4, stemImages.size());
            String raw = callGemini(modelName, prompt, stemImages, avatar);
            drafts = parseResponse(raw);
        }
        log.info("[Gemini] {} done in {}ms: {} pages",
                modelName, System.currentTimeMillis() - start, drafts.size());
        drafts.forEach(d -> log.info("[Gemini]   page: slug={} title={} chars={}",
                d.slug(), d.title(), d.content().length()));
        return drafts;
    }

    /**
     * For STEM avatars with PHOTO uploads, loads the original images from storage
     * so Gemini can see equations/diagrams directly. Capped at {@link #MAX_STEM_IMAGES}.
     */
    List<ImageData> collectStemImages(Avatar avatar, List<KnowledgeFile> files) {
        if (!isStemSubject(avatar.getSubject())) return List.of();

        List<ImageData> images = new ArrayList<>();
        for (KnowledgeFile file : files) {
            if (images.size() >= MAX_STEM_IMAGES) break;
            if (file.getUploadType() != KnowledgeFile.UploadType.PHOTO) continue;
            try {
                byte[] bytes = storagePort.download(file.getStorageKey());
                if (bytes != null && bytes.length > 0) {
                    String mime = inferMimeType(file.getFileName());
                    images.add(new ImageData(bytes, mime));
                    log.debug("[Gemini] Loaded STEM image: {} ({} bytes)", file.getFileName(), bytes.length);
                }
            } catch (Exception e) {
                log.warn("[Gemini] Failed to load image for STEM compile: {} — {}", file.getFileName(), e.getMessage());
            }
        }
        if (!images.isEmpty()) {
            log.info("[Gemini] Including {} STEM images in compile for avatar={}", images.size(), avatar.getId());
        }
        return images;
    }

    record ImageData(byte[] bytes, String mimeType) {}

    static boolean isStemSubject(Subject subject) {
        return subject == Subject.MATHS || subject == Subject.SCIENCE || subject == Subject.CODING;
    }

    private static String inferMimeType(String fileName) {
        if (fileName == null) return "image/jpeg";
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    // Max chars per Gemini chunk: ~25k chars ≈ 6k input tokens, leaves plenty
    // of room in the 16k output-token ceiling for ~60 wiki pages per call.
    private static final int GEMINI_CHUNK_CHARS = 25_000;

    /**
     * Splits the file corpus into 25k-char chunks, compiles each separately,
     * then deduplicates and merges results by slug. Used for documents > 30k chars
     * where a single Gemini call would hit the output-token ceiling.
     */
    private List<WikiPageDraft> compileChunked(String modelName, Avatar avatar,
                                                List<KnowledgeFile> files,
                                                List<WikiPage> existingPages) {
        String allText = files.stream()
                .filter(f -> f.getExtractedText() != null)
                .map(f -> "## Source: " + f.getFileName() + "\n" + f.getExtractedText())
                .collect(java.util.stream.Collectors.joining("\n\n"));

        List<String> chunks = splitIntoChunks(allText, GEMINI_CHUNK_CHARS);
        log.info("[Gemini] {} chunked: {} chunks for {} chars, avatarId={}",
                modelName, chunks.size(), allText.length(), avatar.getId());

        java.util.LinkedHashMap<String, WikiPageDraft> bySlug = new java.util.LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            try {
                String prompt = buildChunkPrompt(avatar, chunk, i + 1, chunks.size(), existingPages);
                String raw = callGemini(modelName, prompt, avatar);
                List<WikiPageDraft> chunkDrafts = parseResponse(raw);
                log.info("[Gemini] {} chunk {}/{}: {} pages", modelName, i + 1, chunks.size(), chunkDrafts.size());
                for (WikiPageDraft d : chunkDrafts) {
                    WikiPageDraft existing = bySlug.get(d.slug());
                    if (existing == null || d.content().length() > existing.content().length()) {
                        bySlug.put(d.slug(), d);
                    }
                }
            } catch (Exception e) {
                log.warn("[Gemini] {} chunk {}/{} failed: {}", modelName, i + 1, chunks.size(), e.getMessage());
            }
        }
        return new java.util.ArrayList<>(bySlug.values());
    }

    private List<String> splitIntoChunks(String text, int chunkSize) {
        List<String> chunks = new java.util.ArrayList<>();
        int pos = 0;
        while (pos < text.length()) {
            int end = Math.min(pos + chunkSize, text.length());
            // Prefer splitting at a section boundary (## or blank line)
            if (end < text.length()) {
                int sectionBound = text.lastIndexOf("\n## ", end);
                int paraBound = text.lastIndexOf("\n\n", end);
                int split = sectionBound > pos + chunkSize / 2 ? sectionBound
                        : paraBound > pos + chunkSize / 2 ? paraBound
                        : end;
                end = split;
            }
            chunks.add(text.substring(pos, end).strip());
            pos = end;
        }
        return chunks;
    }

    private String buildChunkPrompt(Avatar avatar, String chunkText, int chunkNum, int totalChunks,
                                     List<WikiPage> existingPages) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                You are a knowledge organiser for a student study app used by learners aged 13-25.
                Avatar: %s | Subject: %s

                ## TASK
                Convert the extracted text below into structured wiki pages.
                This is chunk %d of %d from a larger document.

                ## RULES
                1. PRESERVE ALL SPECIFIC FACTS — equations, numbers, formulas EXACTLY as stated.
                2. One topic per page. Use markdown: ## headings, - bullets, **bold** for key terms.
                3. Pages: 200-500 words each.
                4. Include a "context" field per page: 1-2 sentences — "This page covers <what> within <subject/topic>".
                5. Reply ONLY with a JSON array — no fences:
                [{"slug":"lowercase-hyphen","title":"Title","content":"markdown","context":"This page covers <what> within <subject/topic>.","prerequisites":["slug-a"]}]

                ## CONTENT (chunk %d of %d):
                %s
                """.formatted(avatar.getName(), avatar.getSubject().name(), chunkNum, totalChunks,
                chunkNum, totalChunks, chunkText));
        return sb.toString();
    }

    /**
     * Text-only Gemini call (backward-compatible).
     */
    private String callGemini(String modelName, String prompt, Avatar avatar) {
        return callGemini(modelName, prompt, List.of(), avatar);
    }

    /**
     * Gemini call with optional inline images for STEM subjects.
     * Images are included as {@code inlineData} parts before the text prompt.
     */
    private String callGemini(String modelName, String prompt, List<ImageData> images, Avatar avatar) {
        // ── Daily quota reset at midnight Asia/Singapore ─────────────────────
        LocalDate today = LocalDate.now(QUOTA_TIMEZONE);
        if (!today.equals(quotaResetDate)) {
            int prev = dailyRequestCount.getAndSet(0);
            quotaResetDate = today;
            log.info("[Gemini] Daily quota counter reset (previous day total={})", prev);
        }

        String url = baseUrl
                + "/v1beta/models/" + modelName
                + ":generateContent?key=" + apiKey;

        // Build parts: images first (if any), then text prompt
        List<Map<String, Object>> parts = new ArrayList<>();
        for (ImageData img : images) {
            Map<String, Object> inlineData = new HashMap<>();
            inlineData.put("mimeType", img.mimeType());
            inlineData.put("data", Base64.getEncoder().encodeToString(img.bytes()));
            parts.add(Map.of("inlineData", inlineData));
        }
        parts.add(Map.of("text", prompt));

        // Thinking control via config (GeminiThinkingBudgetConfig): "wiki-compile" is
        // listed → 0 (thinking OFF — thinking tokens bill at the output rate). This is
        // the one content-generation task where thinking *might* aid quality — spot-
        // check a compiled wiki, and revert by removing the wiki-compile line from
        // gemini.thinking-budget (→ provider default) if quality drops.
        Map<String, Object> generationConfig = new java.util.HashMap<>();
        generationConfig.put("maxOutputTokens", 16384);
        generationConfig.put("temperature", 0.2);
        Integer compileBudget = thinkingConfig.budgetFor("wiki-compile");
        if (compileBudget != null) {
            generationConfig.put("thinkingConfig", Map.of("thinkingBudget", compileBudget));
        }

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of(
                        "parts", parts
                )),
                "generationConfig", generationConfig
        );

        log.debug("[Gemini] POST {} (promptChars={})", url.replaceAll("key=.*", "key=[REDACTED]"), prompt.length());
        long callStart = System.currentTimeMillis();

        // Track the request for quota purposes
        int count = dailyRequestCount.incrementAndGet();
        if (count > QUOTA_WARN_THRESHOLD) {
            log.warn("[Gemini] Daily request count {} approaching 1,500/day free tier cap", count);
        }

        String responseBody;
        try {
            responseBody = webClient.post()
                    .uri(url)
                    .header("Content-Type", "application/json")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(180));
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            if (e.getStatusCode().value() == 429) {
                Instant until = Instant.now().plusMillis(COOLDOWN_DURATION_MS);
                cooldownUntil.set(until);
                log.warn("[Gemini] 429 Rate limited — entering 5min cooldown until {}", until);
            }
            throw new RuntimeException("Gemini API error " + e.getStatusCode() + ": " + e.getMessage(), e);
        }

        log.debug("[Gemini] HTTP response received in {}ms, bodyLen={}",
                System.currentTimeMillis() - callStart,
                responseBody != null ? responseBody.length() : 0);

        if (responseBody == null) {
            throw new RuntimeException("Gemini returned null response");
        }

        // Extract text from Gemini response:
        // {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
        try {
            JsonNode root = objectMapper.readTree(responseBody);

            // Per-user cost: Gemini returns usageMetadata in this SAME body — it was
            // being discarded. Extract + meter (best-effort, never throws). This is
            // the expensive compile path, so it MUST be attributed.
            JsonNode usage = root.path("usageMetadata");
            JsonNode text = root
                    .path("candidates").path(0)
                    .path("content").path("parts").path(0)
                    .path("text");
            boolean empty = text.isMissingNode() || text.asText().isBlank();
            String finishReason = empty
                    ? root.path("candidates").path(0).path("finishReason").asText("unknown")
                    : null;

            // Meter EVERY billed call — success OR empty. On an empty compile Google
            // still billed the prompt + thinking tokens; recording it as success=true
            // (as this used to) hid the exact spend you most need visible. success=false
            // + a finishReason suffix (MAX_TOKENS vs SAFETY) in the purpose_label
            // distinguishes budget-exhaustion from a content refusal without a migration.
            aiUsageMeter.record(
                    avatar != null ? avatar.getUserId() : null,
                    avatar != null ? avatar.getId() : null,
                    callTypeFor(avatar),
                    empty ? "wiki-compile:EMPTY:" + finishReason : "wiki-compile",
                    com.pally.domain.cost.AiTrigger.COMPILE, modelName,
                    usage.path("promptTokenCount").asLong(0),
                    // include thinking tokens (billed as output, separate field)
                    usage.path("candidatesTokenCount").asLong(0)
                            + usage.path("thoughtsTokenCount").asLong(0), !empty, false);

            if (empty) {
                JsonNode promptFeedback = root.path("promptFeedback");
                log.warn("[Gemini] Empty text response. finishReason={} promptFeedback={}",
                        finishReason, promptFeedback);
                log.warn("[Gemini] Full response (first 800 chars): {}",
                        responseBody.substring(0, Math.min(800, responseBody.length())));
                throw new RuntimeException("Gemini returned empty text, finishReason=" + finishReason);
            }
            String textStr = text.asText();
            log.debug("[Gemini] Extracted text length={} preview={}",
                    textStr.length(), textStr.substring(0, Math.min(200, textStr.length())));
            return textStr;
        } catch (Exception e) {
            log.error("[Gemini] Failed to parse response. First 500 chars: {}",
                    responseBody.substring(0, Math.min(500, responseBody.length())));
            throw new RuntimeException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(Avatar avatar, List<KnowledgeFile> files,
                                List<WikiPage> existingPages) {
        StringBuilder sb = new StringBuilder();
        // A MARKING_CORPUS avatar compiles a teacher's marking standard into
        // marking-BEHAVIOUR pages; a normal avatar compiles study notes into
        // topic pages. Everything after the header (source append, merge block,
        // output format) is shared, so merge + JSON parsing are identical.
        sb.append(avatar.isMarkingCorpus()
                ? markingPromptHeader(avatar)
                : avatar.isWeaknessProfile()
                    ? weaknessPromptHeader(avatar)
                    : notesPromptHeader(avatar));

        for (KnowledgeFile file : files) {
            sb.append("\n### Source: ").append(file.getFileName()).append("\n");
            String text = file.getExtractedText();
            if (text != null && !text.isBlank()) {
                sb.append(text).append("\n");
            }
        }

        if (!existingPages.isEmpty()) {
            sb.append("\n## EXISTING PAGES (merge if topics overlap)\n");
            for (WikiPage page : existingPages) {
                sb.append("- slug: ").append(page.getSlug())
                  .append(", title: ").append(page.getTitle()).append("\n");
            }
        }

        sb.append("""

                ## OUTPUT
                Reply ONLY with a JSON array — no markdown fences, no explanation.
                Each object MUST include a "context" field: a 1-2 sentence summary that
                situates the page ("This page covers <what> within <subject/topic>") — it
                is prepended to the page for retrieval, so make it specific.
                [{"slug":"lowercase-hyphen","title":"Title","content":"markdown","context":"This page covers <what> within <subject/topic>.","prerequisites":["slug-a"]}]
                """);

        return sb.toString();
    }

    /** Header for a normal study-notes avatar — topic pages. */
    private String notesPromptHeader(Avatar avatar) {
        return """
                You are a knowledge organiser for a student study app used by learners aged 13-25.

                Avatar name: %s
                Subject: %s

                ## YOUR TASK
                Convert the extracted text below into structured wiki pages.

                ## CRITICAL RULES
                1. PRESERVE ALL SPECIFIC FACTS — equations, numbers, lists, step-by-step
                   processes, experiment procedures, and definitions MUST appear in the
                   wiki page exactly as stated in the source material.
                2. Each wiki page covers ONE topic (e.g., "Photosynthesis", "Electrical Circuits").
                3. Use markdown: ## for headings, - for bullet points, **bold** for key terms.
                4. Use clear, accurate language suitable for secondary and university students (ages 13-25).
                5. Each page: 200-500 words — comprehensive but not overwhelming.

                ## EXAMPLE OUTPUT FORMAT
                [{"slug":"boiling-point-of-water","title":"Boiling Point of Water",
                  "content":"## What is Boiling?\\nBoiling is when water turns from **liquid** to **gas**.\\n\\n## Key Facts\\n- Water boils at **100°C** at sea level\\n- Requires **2260 kJ/kg** of heat energy",
                  "context":"Covers the boiling point of water and latent heat, within states of matter.",
                  "prerequisites":[]}]

                ## EXTRACTED CONTENT
                """.formatted(avatar.getName(), avatar.getSubject().name());
    }

    /**
     * Header for a MARKING_CORPUS avatar — compiles a teacher's rubrics, mark
     * schemes, guidelines and PAST MARKED papers into reusable marking-BEHAVIOUR
     * pages (how marks are awarded, common deductions, model-answer shape, grade
     * bands, comment phrasing) so the AI drafts feedback in THIS centre's style.
     */
    // Marking prompt lives in ONE place (WikiCompilerPrompts) so it can't drift
    // between this (primary) compiler and the Claude fallback.
    private String markingPromptHeader(Avatar avatar) {
        return WikiCompilerPrompts.markingHeader(avatar.getSubject().label());
    }

    // Weakness-profile prompt also lives in WikiCompilerPrompts so it can't drift.
    private String weaknessPromptHeader(Avatar avatar) {
        return WikiCompilerPrompts.weaknessHeader(avatar.getSubject().label());
    }

    private List<WikiPageDraft> parseResponse(String raw) {
        try {
            String json = raw.strip();
            // Strip markdown fences if Gemini wraps the JSON
            if (json.startsWith("```")) {
                int start = json.indexOf('[');
                int end = json.lastIndexOf(']');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            // Find the JSON array boundaries
            int start = json.indexOf('[');
            int end = json.lastIndexOf(']');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }

            JsonNode array = objectMapper.readTree(json);
            List<WikiPageDraft> drafts = new ArrayList<>();
            for (JsonNode node : array) {
                String slug = node.path("slug").asText();
                String title = node.path("title").asText();
                String content = node.path("content").asText();
                String context = node.path("context").asText(null);
                List<String> prereqs = new ArrayList<>();
                JsonNode pre = node.path("prerequisites");
                if (pre.isArray()) {
                    for (JsonNode p : pre) {
                        String ps = p.asText("").trim();
                        if (!ps.isBlank()) prereqs.add(ps);
                    }
                }
                if (!slug.isBlank() && !title.isBlank()) {
                    drafts.add(new WikiPageDraft(slug, title, content, prereqs, context));
                }
            }
            return drafts;
        } catch (Exception e) {
            log.error("[Gemini] Failed to parse wiki pages from response", e);
            throw new RuntimeException("Failed to parse Gemini wiki compilation result: " + e.getMessage(), e);
        }
    }
}
