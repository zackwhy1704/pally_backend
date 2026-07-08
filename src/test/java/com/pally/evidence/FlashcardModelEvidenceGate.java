package com.pally.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE-1 EVIDENCE GATE — Haiku vs Gemini-Flash for flashcard generation.
 * "No model switch ships without this passing." Quality is the moat.
 *
 * <p>WHAT IT DOES: for each page in the corpus it sends the EXACT production
 * flashcard prompt to BOTH claude-haiku (current) and gemini-2.5-flash
 * (candidate), parses the cards, and scores each card with the SAME rule the
 * generator uses (parses + non-blank front + non-blank back — note:
 * RulesOutputValidator has NO flashcard type, flashcards are validated inline).
 * It writes a side-by-side dump and prints an explicit GATE PASSED/FAILED line.
 * The build FAILS if Flash's pass-rate is more than {@code TOLERANCE_PTS} below
 * Haiku's — that failure IS the gate protecting the moat.
 *
 * <p>HOW TO RUN (~10 min):
 * <pre>
 *   export ANTHROPIC_API_KEY=sk-ant-...      # your Claude key
 *   export GEMINI_API_KEY=AIza...            # your Google AI Studio / Gemini key
 *   # optional: export CLAUDE_HAIKU_MODEL=claude-haiku-4-5-20251001  (defaults below)
 *   # optional: export EVIDENCE_PAGES_FILE=/path/to/your/20-real-pages.txt
 *   ./gradlew test --tests com.pally.evidence.FlashcardModelEvidenceGate
 * </pre>
 * Without both keys the test SKIPS (so CI never runs it). The side-by-side dump
 * lands at {@code build/evidence/flashcard-gate.txt} — READ IT before trusting
 * the number; the pass-rate is a floor, the human eyeball on the dump is the moat.
 *
 * <p>THE CORPUS: the bundled fixture is 5 PLACEHOLDER pages so it runs out of the
 * box. For a TRUSTWORTHY verdict, point EVIDENCE_PAGES_FILE at ~20 REAL compiled
 * wiki pages from a mixed-subject avatar (same "=== TITLE ===" then body format).
 *
 * <p>ON PASS: the one-line production flip is —
 *   add {@code ModelRouter.forFlashcardGeneration()} returning gemini-2.5-flash
 *   and route ClaudeFlashcardGenerator through GeminiCompletionService for that
 *   purpose (config-driven, revertible). See DEFERRED.md "Flashcard model lever".
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class FlashcardModelEvidenceGate {

    /** Flash may not be MORE than this many points below Haiku (the hard gate). */
    private static final double TOLERANCE_PTS = 2.0;
    private static final int MAX_TOKENS = 900; // matches ClaudeFlashcardGenerator
    private static final String GEMINI_MODEL = "gemini-2.5-flash";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();

    private record Page(String title, String body) {}
    private record ModelScore(int pages, int totalCards, int validCards, List<Integer> perPage) {
        double passRatePct() { return totalCards == 0 ? 0.0 : 100.0 * validCards / totalCards; }
        double avgCardsPerPage() { return pages == 0 ? 0.0 : (double) totalCards / pages; }
    }

    @Test
    void flashPassRateWithinToleranceOfHaiku() throws Exception {
        String claudeKey = System.getenv("ANTHROPIC_API_KEY");
        String geminiKey = System.getenv("GEMINI_API_KEY");
        String haikuModel = System.getenv().getOrDefault("CLAUDE_HAIKU_MODEL",
                "claude-haiku-4-5-20251001");

        List<Page> pages = loadPages();
        assertThat(pages).as("corpus must be non-empty").isNotEmpty();

        StringBuilder dump = new StringBuilder();
        dump.append("FLASHCARD MODEL EVIDENCE GATE\n")
            .append("Haiku model: ").append(haikuModel).append("\n")
            .append("Gemini model: ").append(GEMINI_MODEL).append("\n")
            .append("Pages: ").append(pages.size()).append("\n")
            .append("=".repeat(72)).append("\n\n");

        int hTotal = 0, hValid = 0, gTotal = 0, gValid = 0;
        List<Integer> hPer = new ArrayList<>(), gPer = new ArrayList<>();

        for (Page p : pages) {
            String prompt = flashcardPrompt(p.title(), p.body());
            List<String[]> haikuCards = safe(() -> parseCards(callClaude(claudeKey, haikuModel, prompt)));
            List<String[]> geminiCards = safe(() -> parseCards(callGemini(geminiKey, prompt)));

            int hv = countValid(haikuCards), gv = countValid(geminiCards);
            hTotal += haikuCards.size(); hValid += hv; hPer.add(haikuCards.size());
            gTotal += geminiCards.size(); gValid += gv; gPer.add(geminiCards.size());

            dump.append("### ").append(p.title()).append("\n");
            dump.append("-- HAIKU (").append(hv).append("/").append(haikuCards.size()).append(" valid) --\n");
            haikuCards.forEach(c -> dump.append("  Q: ").append(c[0]).append("\n  A: ").append(c[1]).append("\n"));
            dump.append("-- GEMINI-FLASH (").append(gv).append("/").append(geminiCards.size()).append(" valid) --\n");
            geminiCards.forEach(c -> dump.append("  Q: ").append(c[0]).append("\n  A: ").append(c[1]).append("\n"));
            dump.append("\n");
        }

        ModelScore haiku = new ModelScore(pages.size(), hTotal, hValid, hPer);
        ModelScore gemini = new ModelScore(pages.size(), gTotal, gValid, gPer);
        double delta = gemini.passRatePct() - haiku.passRatePct();
        boolean passed = delta >= -TOLERANCE_PTS;

        String verdict = String.format(
                "GATE %s: Flash %.1f%% vs Haiku %.1f%% (delta %+.1f pts; tolerance -%.1f). "
                + "cards/page Haiku %.1f, Flash %.1f.",
                passed ? "PASSED" : "FAILED",
                gemini.passRatePct(), haiku.passRatePct(), delta, TOLERANCE_PTS,
                haiku.avgCardsPerPage(), gemini.avgCardsPerPage());

        dump.append("=".repeat(72)).append("\n").append(verdict).append("\n");
        Path out = Path.of("build", "evidence", "flashcard-gate.txt");
        Files.createDirectories(out.getParent());
        Files.writeString(out, dump.toString());

        System.out.println("\n" + verdict);
        System.out.println("Side-by-side dump: " + out.toAbsolutePath()
                + "  (READ IT — the pass-rate is a floor; your eyeball is the moat)\n");

        assertThat(delta)
                .as("Flash pass-rate must be within %.1f pts of Haiku (see %s). %s",
                        TOLERANCE_PTS, out.toAbsolutePath(), verdict)
                .isGreaterThanOrEqualTo(-TOLERANCE_PTS);
    }

    // ── the EXACT production flashcard prompt (kept in sync with the generator) ──
    private String flashcardPrompt(String title, String content) {
        return """
                Create 3–5 spaced-repetition flashcards from this study material.
                Each card has a short "front" question and a concise "back" answer
                drawn directly from the content. Cover the most important facts /
                concepts — not trivia.

                Title: %s
                Content:
                %s

                Reply with ONLY a JSON array (no markdown fence, no commentary):
                [{"front":"...","back":"..."}, ...]
                """.formatted(title, content.length() > 2500 ? content.substring(0, 2500) : content);
    }

    // ── validity: the SAME rule the generator uses (non-blank front + back) ──────
    private int countValid(List<String[]> cards) {
        int v = 0;
        for (String[] c : cards) if (!c[0].isBlank() && !c[1].isBlank()) v++;
        return v;
    }

    private List<String[]> parseCards(String raw) {
        List<String[]> cards = new ArrayList<>();
        if (raw == null || raw.isBlank()) return cards;
        String json = raw.strip().replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").strip();
        int first = json.indexOf('['), last = json.lastIndexOf(']');
        if (first < 0 || last <= first) return cards;
        try {
            JsonNode arr = mapper.readTree(json.substring(first, last + 1));
            for (JsonNode n : arr) {
                cards.add(new String[]{ n.path("front").asText("").strip(),
                        n.path("back").asText("").strip() });
            }
        } catch (Exception ignored) { /* unparseable → zero valid, the point of the gate */ }
        return cards;
    }

    // ── raw provider calls (self-contained; no Spring/DB) ────────────────────────
    private String callClaude(String key, String model, String prompt) throws Exception {
        var body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", MAX_TOKENS);
        var messages = body.putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");
        msg.put("content", prompt);
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", key)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException("Claude " + res.statusCode() + ": " + res.body());
        return mapper.readTree(res.body()).path("content").path(0).path("text").asText("");
    }

    private String callGemini(String key, String prompt) throws Exception {
        var body = mapper.createObjectNode();
        var parts = body.putArray("contents").addObject().putArray("parts").addObject();
        parts.put("text", prompt);
        var gen = body.putObject("generationConfig");
        gen.put("maxOutputTokens", MAX_TOKENS);
        gen.put("temperature", 0.2);
        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + GEMINI_MODEL + ":generateContent?key=" + key;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException("Gemini " + res.statusCode() + ": " + res.body());
        return mapper.readTree(res.body())
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
    }

    private interface Call { List<String[]> get() throws Exception; }
    private List<String[]> safe(Call c) {
        try { return c.get(); }
        catch (Exception e) { System.out.println("  [call failed: " + e.getMessage() + "]"); return List.of(); }
    }

    private List<Page> loadPages() throws Exception {
        String file = System.getenv("EVIDENCE_PAGES_FILE");
        String raw;
        if (file != null && !file.isBlank()) {
            raw = Files.readString(Path.of(file));
        } else {
            try (var in = getClass().getResourceAsStream("/evidence/flashcard-pages.txt")) {
                raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        List<Page> pages = new ArrayList<>();
        String title = null;
        StringBuilder body = new StringBuilder();
        for (String line : raw.split("\n")) {
            if (line.startsWith("#")) continue;                       // comment
            if (line.matches("^===\\s.+\\s===$")) {                   // page header
                if (title != null) pages.add(new Page(title, body.toString().strip()));
                title = line.replaceAll("^===\\s|\\s===$", "").strip();
                body.setLength(0);
            } else if (title != null) {
                body.append(line).append("\n");
            }
        }
        if (title != null && !body.toString().isBlank()) pages.add(new Page(title, body.toString().strip()));
        return pages;
    }
}
