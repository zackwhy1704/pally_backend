package com.pally.domain.chat;

import com.pally.domain.avatar.TeachingMode;
import com.pally.domain.knowledge.WikiPage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins two defects found by field-verifying the frustration signal (Task 3).
 *
 * <p><b>DEFECT 1 — DIRECT-mode inertness.</b> {@code buildContent} returned the
 * ANSWER-mode string BEFORE {@code shouldEscape} was ever tested, so the frustration
 * signal was computed, logged, and discarded for every DIRECT-mode avatar. Proven at
 * the time by byte-identical output for escalate=true and escalate=false — the same
 * "computed then silently dropped" bug class as the original
 * ClaudeContextAssembler defect, surviving in a different branch.
 *
 * <p><b>DEFECT 2 — note-citation never fired.</b> The live chat path called the 4-arg
 * {@code buildBlock4} overload, which substitutes {@code List.of()}, so
 * {@code buildNoteCitation} always returned empty and "cite the student's own notes"
 * never appeared in an assembled prompt.
 *
 * <p><b>REGRESSION GUARD.</b> The GUIDE escalated block is a VERIFIED ASSET — a live
 * Claude run showed it changes the reply from a 23–32 token bare question into a
 * 175-token worked sub-step that teaches the reciprocal rule and applies it. Nothing
 * in this change may drift it, so its text is pinned byte-for-byte below.
 */
class SocraticBlock4GapsTest {

    private final SocraticPromptBuilder builder = new SocraticPromptBuilder();

    private String block(TeachingMode mode, boolean escalate, List<WikiPage> pages) {
        return (String) builder.buildBlock4(mode, Optional.empty(), 4, escalate, pages).get("text");
    }

    private List<WikiPage> pages() {
        return List.of(
                WikiPage.create("av-1", "dividing-fractions", "Dividing Fractions", "flip and multiply"),
                WikiPage.create("av-1", "reciprocals", "Reciprocals", "the reciprocal of a/b is b/a"));
    }

    // ── DEFECT 1 ─────────────────────────────────────────────────────────────

    @Test
    void directMode_escalatedBlock_differsFromNonEscalated() {
        // THE EXACT EQUALITY THAT WAS THE BUG. If these ever match again, the
        // frustration signal is inert in ANSWER mode once more.
        String calm = block(TeachingMode.DIRECT, false, List.of());
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated)
                .as("DIRECT-mode escalation must change the prompt, not be discarded")
                .isNotEqualTo(calm);
    }

    @Test
    void directMode_escalated_stillDeliversTheCompleteAnswer_neverWithholdsIt() {
        // The critical pedagogy constraint: escalation here must NOT copy GUIDE's
        // escape-valve language. Withdrawing the answer would punish a struggling
        // student by removing help they were already receiving.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated).doesNotContain("Do NOT give the full final answer yet");
        assertThat(escalated).doesNotContain("WORKED SUB-STEP");
        assertThat(escalated).contains("Do NOT withdraw the answer");
    }

    @Test
    void directMode_escalated_presumesConfusion_ratherThanAskingIfAnyExists() {
        // "Does that make sense?" invites a face-saving yes from exactly the student
        // who needs to say no.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated).contains("Which step would you like me to explain differently?");
        // Whitespace-normalised: the block is line-wrapped, so this must not depend
        // on where the wrap happens to fall.
        assertThat(escalated.replaceAll("\\s+", " "))
                .contains("never \"does that make sense?\"");
    }

    @Test
    void directMode_escalated_forbidsShortening_theLikelyModelMisreading() {
        // Without this, a model tends to read "they're confused" as "be more concise",
        // which is the opposite of what a lost student needs.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated).contains("must be LONGER than your usual answer");
    }

    @Test
    void directMode_nonEscalated_isUnchanged() {
        // The calm ANSWER-mode path must be untouched by this fix.
        String calm = block(TeachingMode.DIRECT, false, List.of());

        assertThat(calm).isEqualTo("""
                The student wants a direct answer. Produce a clear, complete worked solution:
                1. State the answer first.
                2. Show the key steps concisely.
                3. End with a brief "quiz yourself" nudge — one question that lets them check
                   their own understanding (e.g. "Can you now do a similar problem without
                   looking?"). Keep the nudge warm and one sentence.
                Do NOT ask guiding Socratic questions — give the complete answer directly.
                """);
    }

    // ── DEFECT 2 ─────────────────────────────────────────────────────────────

    @Test
    void guideMode_withWikiPages_emitsANonEmptyCitationLine() {
        String withPages = block(TeachingMode.TEACHING, false, pages());

        assertThat(withPages).contains("Cite where this appears in the student's own notes");
        assertThat(withPages).contains("Dividing Fractions");
        assertThat(withPages).contains("Reciprocals");
    }

    @Test
    void guideMode_withoutWikiPages_emitsNoCitationLine() {
        // Absence must stay silent — never a dangling "Cite ... ''" with no titles.
        String withoutPages = block(TeachingMode.TEACHING, false, List.of());

        assertThat(withoutPages).doesNotContain("Cite where this appears");
    }

    @Test
    void citationLandsInExactlyTheKnownBranches_notAsASurprise() {
        // The "dead line now live" set, made explicit: default GUIDE gets it; DIRECT
        // (both states) and escalated GUIDE do NOT — those branches never referenced
        // noteCitation.
        assertThat(block(TeachingMode.TEACHING, false, pages()))
                .as("default GUIDE — citation lands here").contains("Cite where this appears");
        assertThat(block(TeachingMode.TEACHING, true, pages()))
                .as("escalated GUIDE — no citation, branch never used it")
                .doesNotContain("Cite where this appears");
        assertThat(block(TeachingMode.DIRECT, false, pages()))
                .as("calm DIRECT — no citation").doesNotContain("Cite where this appears");
        assertThat(block(TeachingMode.DIRECT, true, pages()))
                .as("escalated DIRECT — no citation").doesNotContain("Cite where this appears");
    }

    // ── OUTPUT-SHAPE SCAFFOLD (the falsifiability the section rewrite buys) ──

    @Test
    void directEscalated_requiresBOTHNamedSections_conceptAndSteps() {
        // Field verification showed the prose version reliably DROPPED the step
        // decomposition: the model read "explain the concept" and "give numbered
        // steps" as competing options and kept only the first. Named sections make
        // the omission visible in output shape — and assertable here, which prose
        // instructions never were.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated).contains("**The idea underneath**");
        assertThat(escalated).contains("**The steps**");
        assertThat(escalated).contains("MUST contain BOTH of these sections");
    }

    @Test
    void directEscalated_sectionHeadersAreConversational_notFormFields() {
        // A frustrated student should not be handed something that reads like a form.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated).doesNotContain("SECTION 1");
        assertThat(escalated).doesNotContain("Answer:");
        assertThat(escalated).doesNotContain("Field");
    }

    @Test
    void directEscalated_statesItIsGoingBack_ratherThanSilentlyReSolving() {
        // Observed deviation: with no new problem in the message, the model reached
        // back and re-solved the PREVIOUS question with no signal. Re-solving is
        // right; doing it silently reads as though the tutor misheard them.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated).contains("MOST RECENT problem");
        assertThat(escalated).contains("Let's go back to the last one");
        assertThat(escalated).contains("never silently answer a different question");
    }

    @Test
    void directEscalated_keepsConceptBeforeSteps_notReordered() {
        // Reordering would satisfy instruction-following while abandoning the
        // pedagogy: the concept leads precisely BECAUSE repeated correct
        // demonstrations already failed, so the arithmetic was never the problem.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated.indexOf("**The idea underneath**"))
                .as("concept section must precede the steps section")
                .isLessThan(escalated.indexOf("**The steps**"));
    }

    @Test
    void directEscalated_addsNoEmphasisWordInflation() {
        // The rejected weak fix. Emphasis words are unfalsifiable and lengthen the
        // block, which is itself a contributor to instruction-dropping.
        String escalated = block(TeachingMode.DIRECT, true, List.of());

        assertThat(escalated).doesNotContain("CRITICAL");
        assertThat(escalated).doesNotContain("ALWAYS");
        assertThat(escalated).doesNotContain("VERY IMPORTANT");
    }

    // ── REGRESSION GUARD on the Task-3-verified asset ────────────────────────

    @Test
    void guideEscalatedBlock_isByteIdenticalToTheVerifiedText() {
        // Pinned verbatim. This exact string was field-verified against the live
        // Claude API: it turned a 23–32 token bare guiding question into a 175-token
        // reply that names the reciprocal rule, applies it, and hands back. It must
        // not drift — including by accidentally gaining a citation line.
        String escalatedGuide = block(TeachingMode.TEACHING, true, pages());

        assertThat(escalatedGuide).isEqualTo(
                "The student has made 4 genuine attempts. "
                + "Acknowledge their effort warmly. Now give a WORKED SUB-STEP — "
                + "walk through the first part of the reasoning fully, then ask "
                + "them to complete the rest. Do NOT give the full final answer yet. "
                + "This is the escape-valve hint, not the solution.");
    }

    @Test
    void guideEscalatedBlock_isIdenticalWithAndWithoutWikiPages() {
        // Defect 2 must not leak into the verified escalated-GUIDE asset.
        assertThat(block(TeachingMode.TEACHING, true, pages()))
                .isEqualTo(block(TeachingMode.TEACHING, true, List.of()));
    }
}
