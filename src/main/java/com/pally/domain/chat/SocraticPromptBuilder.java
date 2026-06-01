package com.pally.domain.chat;

import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.knowledge.WikiPage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Builds the Block 4 dynamic tail for the prompt caching system.
 * Block 4 has no cache_control — it changes per message based on Socratic state.
 *
 * <p><b>GUIDE mode (TeachingMode.TEACHING)</b> — five-stage Socratic pipeline:
 * <ol>
 *   <li>Internally verify the correct answer before guiding (never lead to a wrong destination)</li>
 *   <li>Name the topic(s) and cite the student's own notes</li>
 *   <li>Name the prerequisite concepts needed</li>
 *   <li>Lay out the shape of the reasoning — not the answer</li>
 *   <li>Ask ONE guiding question; wait; never volunteer the final answer</li>
 * </ol>
 * Hint ladder: each hint reveals slightly more until the effort threshold is reached;
 * only then a worked sub-step (not the final answer) is offered.
 *
 * <p><b>ANSWER mode (TeachingMode.DIRECT)</b> — give the verified worked solution
 * with concise steps, then a "quiz yourself" nudge.
 */
@Component
public class SocraticPromptBuilder {

    /**
     * Builds Block 4 map for inclusion in the system blocks array.
     *
     * @param wikiPages     the retrieved pages for this avatar (used for note citations)
     * @param attemptCount  number of attempts this session (drives hint ladder)
     * @param shouldEscape  true when the effort threshold has been met
     */
    public Map<String, Object> buildBlock4(
            TeachingMode mode,
            Optional<SocraticHintTree> hintTree,
            int attemptCount,
            boolean shouldEscape,
            List<WikiPage> wikiPages
    ) {
        String content = buildContent(mode, hintTree, attemptCount, shouldEscape, wikiPages);
        return Map.of("type", "text", "text", content);
    }

    /** Backwards-compatible overload for call sites that don't pass wiki pages yet. */
    public Map<String, Object> buildBlock4(
            TeachingMode mode,
            Optional<SocraticHintTree> hintTree,
            int attemptCount,
            boolean shouldEscape
    ) {
        return buildBlock4(mode, hintTree, attemptCount, shouldEscape, List.of());
    }

    private String buildContent(
            TeachingMode mode,
            Optional<SocraticHintTree> hintTree,
            int attemptCount,
            boolean shouldEscape,
            List<WikiPage> wikiPages
    ) {
        // ── ANSWER mode ───────────────────────────────────────────────────────
        if (mode == TeachingMode.DIRECT) {
            return """
                    The student wants a direct answer. Produce a clear, complete worked solution:
                    1. State the answer first.
                    2. Show the key steps concisely.
                    3. End with a brief "quiz yourself" nudge — one question that lets them check
                       their own understanding (e.g. "Can you now do a similar problem without
                       looking?"). Keep the nudge warm and one sentence.
                    Do NOT ask guiding Socratic questions — give the complete answer directly.
                    """;
        }

        // ── GUIDE mode ────────────────────────────────────────────────────────
        // Build a short note-citation line if we have wiki pages.
        String noteCitation = buildNoteCitation(wikiPages);

        // Escape valve: genuine effort reached → worked sub-step (not the final answer).
        if (shouldEscape) {
            return "The student has made " + attemptCount + " genuine attempts. "
                    + "Acknowledge their effort warmly. Now give a WORKED SUB-STEP — "
                    + "walk through the first part of the reasoning fully, then ask "
                    + "them to complete the rest. Do NOT give the full final answer yet. "
                    + "This is the escape-valve hint, not the solution.";
        }

        // Hint-tree guided question for this attempt.
        if (hintTree.isPresent() && !hintTree.get().getHints().isEmpty()) {
            SocraticHintTree tree = hintTree.get();
            List<SocraticHintTree.HintStep> hints = tree.getHints();
            int stepIndex = Math.min(attemptCount, hints.size() - 1);
            SocraticHintTree.HintStep step = hints.get(stepIndex);

            String hintSuffix = attemptCount > 0
                    ? " This is hint " + (attemptCount + 1) + " — reveal a little more of the scaffold."
                    : "";

            return """
                    GUIDE MODE — five-stage Socratic pipeline. Follow this structure for your reply:

                    1. TOPIC (one line): State which concept this question is about.
                    %s
                    2. PREREQUISITE CHECK: Name what the student needs to know to answer this.
                       If their notes show they haven't covered a prerequisite, briefly teach
                       that concept first (2–3 sentences), then continue guiding.
                    3. REASONING SHAPE: Lay out the shape of the approach — not the answer.
                       E.g. "To solve this, we need to figure out X, then use X to find Y."
                    4. GUIDING QUESTION (one question only): "%s"%s
                       Wait for the student's response before continuing.

                    STRICT RULES:
                    - NEVER state the final answer outright.
                    - If the student says "I don't know" → scaffold DOWN to a smaller sub-question.
                    - If they say "just tell me" or "idk" repeatedly → keep scaffolding down,
                      do NOT give the answer (it will appear after %d genuine attempts).
                    - Affirm correct reasoning warmly; probe partial answers with follow-up questions.
                    - Log any misconception you detect (the backend harness picks these up).
                    """.formatted(noteCitation, step.guidingQuestion(), hintSuffix,
                    getEscapeThreshold());
        }

        // Default GUIDE prompt (no hint tree).
        return """
                GUIDE MODE — five-stage Socratic pipeline. Follow this structure:

                1. TOPIC (one line): Name the topic this question tests.
                %s
                2. PREREQUISITE CHECK: Name what the student needs to know.
                   If a prerequisite is missing from their notes, briefly teach it first.
                3. REASONING SHAPE: Outline the approach without revealing the answer.
                4. GUIDING QUESTION (one only): Ask one targeted question that leads the
                   student one step closer to the answer. Wait for their reply.

                STRICT RULES:
                - NEVER state the final answer.
                - "I don't know" → scaffold to a smaller question.
                - Gaming ("just tell me", repeated empty replies) → keep scaffolding, don't reward.
                - Affirm correct steps; probe gaps with questions, not corrections.
                """.formatted(noteCitation);
    }

    private String buildNoteCitation(List<WikiPage> wikiPages) {
        if (wikiPages == null || wikiPages.isEmpty()) return "";
        String titles = wikiPages.stream()
                .limit(3)
                .map(WikiPage::getTitle)
                .collect(Collectors.joining(", "));
        return "   Cite where this appears in the student's own notes: \""
                + titles + "\" (from their uploaded material).";
    }

    private int getEscapeThreshold() {
        return 3; // matches ChatSession.ESCAPE_HATCH_THRESHOLD
    }

    /** Formats hint steps for logging/debugging. */
    public static String summariseHints(SocraticHintTree tree) {
        return tree.getHints().stream()
                .map(h -> "Step " + h.stepNumber() + ": " + h.guidingQuestion())
                .collect(Collectors.joining(" | "));
    }
}
