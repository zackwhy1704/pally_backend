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

    private final ClaudeApiClient claudeApiClient;
    private final ObjectMapper objectMapper;
    private final ModelRouter modelRouter;
    private final FlashcardRepository flashcardRepository;

    /// New page → generate + persist (delete any prior cards on the same
    /// slug first, defensively).
    public void generateAndSaveForPage(String avatarId, WikiPage page) {
        flashcardRepository.deleteByAvatarIdAndSourceSlug(
                avatarId, page.getSlug());
        List<FlashCard> cards = generateFromContent(
                avatarId, page.getSlug(), page.getTitle(), page.getContent());
        if (!cards.isEmpty()) {
            flashcardRepository.saveAll(cards);
            log.info("[Flashcard] Generated {} cards for slug={}",
                    cards.size(), page.getSlug());
        }
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
            raw = claudeApiClient.complete(
                    modelRouter.getHaikuModel(), MAX_TOKENS, prompt);
        } catch (Exception e) {
            log.warn("[Flashcard] Claude call failed slug={}: {}",
                    slug, e.getMessage());
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
