package com.pally.infrastructure.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.quiz.FlashCard;
import com.pally.domain.quiz.FlashcardRepository;
import com.pally.shared.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Generates spaced-repetition flashcards from a single wiki page using
 * Claude Haiku. Called from {@code CompileWikiUseCase} so cards stay in
 * sync with the underlying wiki content — every page create/update
 * regenerates its cards.
 *
 * <p>SM-2 defaults are applied at insert time: {@code repetitions=0},
 * {@code easeFactor=2.5}, {@code intervalDays=1}, due immediately so the
 * Home banner picks them up right away.
 */
@Component
@RequiredArgsConstructor
public class ClaudeFlashcardGenerator {

    private static final Logger log =
            LoggerFactory.getLogger(ClaudeFlashcardGenerator.class);
    private static final int MAX_TOKENS = 900;
    /** ai_usage purpose label — same on both providers so the ledger gives a clean
     *  Haiku-vs-Gemini before/after for the exact same workload. */
    private static final String PURPOSE = "flashcard-gen";

    private final ClaudeApiClient claudeApiClient;
    private final GeminiCompletionService geminiCompletion;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;
    private final FlashcardRepository flashcardRepository;

    /**
     * The ~$1.25/upload cost lever. When true, flashcard generation routes to
     * gemini-2.5-flash (with {@code gemini.thinking-budget.flashcard-gen=0} — the
     * evidence gate only passed with thinking OFF) instead of Claude Haiku.
     * Config-flippable via {@code FLASHCARD_USE_GEMINI} so it reverts with no deploy.
     * Defaults FALSE: merging this changes nothing until the flag is flipped AFTER
     * the real 20-page evidence run. GeminiCompletionService falls back to Haiku if
     * the Gemini key is absent, so a flip can never hard-fail generation.
     */
    @Value("${flashcard.use-gemini:false}")
    private boolean useGemini;

    /// New page → generate + persist (delete any prior cards on the same
    /// slug first, defensively).
    public void generateAndSaveForPage(String avatarId, WikiPage page) {
        flashcardRepository.deleteByAvatarIdAndSourceSlug(
                avatarId, page.getSlug());
        List<FlashCard> cards = generateFromContent(
                avatarId, page.getSlug(), page.getTitle(), page.getContent());
        // Cards-per-page counter so a model-swap quality regression is visible in the
        // first day's logs (grep "[Flashcard] cards/page"). The evidence gate's failure
        // mode was 0-card pages (thinking-ON silently ate the output budget) — surface
        // an empty result LOUDLY on non-blank content, it's the drop signal.
        String model = useGemini ? "gemini-2.5-flash" : "haiku";
        if (cards.isEmpty()) {
            boolean hadContent = page.getContent() != null && !page.getContent().isBlank();
            if (hadContent) {
                log.warn("[Flashcard] cards/page=0 model={} slug={} — DROP on non-blank content "
                        + "(possible model regression)", model, page.getSlug());
            }
            return;
        }
        flashcardRepository.saveAll(cards);
        log.info("[Flashcard] cards/page={} model={} slug={}",
                cards.size(), model, page.getSlug());
    }

    /// Updated page → same as the new-page path (delete then regenerate).
    public void regenerateForPage(String avatarId, WikiPage page) {
        generateAndSaveForPage(avatarId, page);
    }

    private List<FlashCard> generateFromContent(
            String avatarId, String slug, String title, String content) {
        if (content == null || content.isBlank()) return List.of();

        String prompt = """
                Create 3–5 spaced-repetition flashcards from this study material.
                Each card has a short "front" question and a concise "back" answer
                drawn directly from the content. Cover the most important facts /
                concepts — not trivia.

                Title: %s
                Content:
                %s

                Reply with ONLY a JSON array (no markdown fence, no commentary):
                [{"front":"...","back":"..."}, ...]
                """.formatted(title, truncate(content, 2500));

        String raw;
        try {
            // Both paths ledger under PURPOSE ("flashcard-gen") so Haiku (pre-flip) and
            // Gemini (post-flip) costs are directly comparable in ai_usage. The Gemini
            // path applies gemini.thinking-budget.flashcard-gen=0, meters with avatarId,
            // and itself falls back to Haiku if the Gemini key is absent.
            raw = useGemini
                    ? geminiCompletion.complete(MAX_TOKENS, prompt, PURPOSE, avatarId)
                    : claudeApiClient.complete(
                            modelRouter.getHaikuModel(), MAX_TOKENS, prompt, PURPOSE);
        } catch (Exception e) {
            log.warn("[Flashcard] generation call failed (useGemini={}) slug={}: {}",
                    useGemini, slug, e.getMessage());
            return List.of();
        }
        if (raw == null || raw.isBlank()) return List.of();
        String json = raw.strip();
        if (json.startsWith("```")) {
            json = json.replaceAll("```[a-z]*\\n?", "")
                    .replaceAll("```", "")
                    .strip();
        }
        int first = json.indexOf('[');
        int last = json.lastIndexOf(']');
        if (first < 0 || last <= first) return List.of();
        json = json.substring(first, last + 1);

        List<Map<String, Object>> parsed;
        try {
            parsed = objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("[Flashcard] parse failed slug={}: {}", slug, e.getMessage());
            return List.of();
        }

        List<FlashCard> cards = new ArrayList<>(parsed.size());
        Instant now = Instant.now();
        for (Map<String, Object> entry : parsed) {
            Object front = entry.get("front");
            Object back = entry.get("back");
            if (front == null || back == null) continue;
            String f = front.toString().strip();
            String b = back.toString().strip();
            if (f.isEmpty() || b.isEmpty()) continue;
            cards.add(new FlashCard(
                    IdGenerator.newId(),
                    avatarId,
                    f,
                    b,
                    slug,
                    null,    // never rated yet
                    now,     // due now so the user sees them on next open
                    0,       // repetitions
                    2.5,     // SM-2 starting ease factor
                    1        // SM-2 starting interval (days)
            ));
        }
        return cards;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
