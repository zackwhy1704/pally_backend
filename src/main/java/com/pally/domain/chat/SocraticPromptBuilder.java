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
            // Escalation in ANSWER mode means something CATEGORICALLY different from
            // GUIDE. In GUIDE, escalation resolves a WITHHOLDING problem — the tutor
            // is refusing to answer and finally releases a worked sub-step. Here the
            // student is ALREADY getting complete answers, so frustration means the
            // answers are arriving and not landing.
            //
            // So this must NOT reuse GUIDE's escape-valve language: "do NOT give the
            // full final answer yet" contradicts this mode's entire contract and would
            // punish a struggling student by withdrawing help they were already getting.
            // Nothing here reduces what the student receives — it slows down, justifies
            // each step, and surfaces the missing prerequisite.
            //
            // Previously this branch returned before shouldEscape was ever tested, so
            // the frustration signal was computed, logged, and silently discarded for
            // every DIRECT-mode avatar — the same "computed then dropped" bug class as
            // the original ClaudeContextAssembler defect, surviving in another branch.
            if (shouldEscape) {
                // Structured as REQUIRED OUTPUT SECTIONS, not prose instructions.
                // Field verification against the live model showed the previous prose
                // version reliably dropped the step-decomposition instruction: the model
                // treated "explain the concept" and "give numbered steps" as competing
                // descriptions of how to explain, picked the first, and produced a
                // coherent reply missing the steps entirely. Named sections make an
                // omission visible in the OUTPUT SHAPE rather than as a missing quality —
                // which also makes it assertable in a test.
                //
                // Section headers are deliberately conversational ("The idea underneath",
                // "The steps") rather than clinical — a frustrated student should not be
                // handed something that reads like a filled-in form.
                //
                // Order is deliberate and must NOT be flipped to put steps first: the
                // concept leads because repeated confusion across several correct
                // demonstrations means the arithmetic was never the problem. Reordering
                // would make the model follow instructions while abandoning the pedagogy.
                return """
                        The student is getting complete answers but they are NOT landing — they have
                        signalled confusion or frustration. Do NOT withdraw the answer and do NOT switch
                        to asking them questions instead. They still get the full worked solution, but
                        restructured.

                        If their message names no new problem, work through the MOST RECENT problem they
                        attempted, and say so in your opening line (e.g. "Let's go back to the last one")
                        — never silently answer a different question than the one they asked, which reads
                        as though you misheard them.

                        Your reply MUST contain BOTH of these sections, in this order, each under its own
                        short heading. A reply missing either section is incomplete:

                        **The idea underneath** — name the single concept this solution depends on and
                        explain it plainly in 2–3 sentences, before touching the problem. Repeated
                        confusion across several answers is usually ONE missing prerequisite, not this
                        specific question.

                        **The steps** — the complete solution as SMALL numbered steps. Every step states
                        both WHAT you did and WHY, one short line of reasoning each. A student who can
                        follow the arithmetic and still feels lost is missing the why, not the what.

                        Open with one warm sentence acknowledging the difficulty; do not dwell on it.
                        Close by asking "Which step would you like me to explain differently?" — never
                        "does that make sense?", which invites a face-saving "yes" from the student who
                        most needs to say no. Set no new practice problem.

                        This reply must be LONGER than your usual answer, never shorter.
                        """;
            }
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
