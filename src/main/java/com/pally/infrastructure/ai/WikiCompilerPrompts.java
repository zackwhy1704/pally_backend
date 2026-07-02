package com.pally.infrastructure.ai;

/**
 * Single source of truth for wiki-compile prompt text shared by BOTH
 * {@link GeminiWikiCompiler} (primary) and {@link ClaudeWikiCompiler} (fallback).
 *
 * <p>The marking-corpus prompt in particular MUST be one definition: if the two
 * compilers carried their own copies they could drift, and the compiled marking
 * standard would then differ by which tier happened to serve the compile. Each
 * compiler still appends its own source list + output-format/JSON-parsing after
 * this header — only the shared instruction text lives here.
 */
final class WikiCompilerPrompts {

    private WikiCompilerPrompts() {}

    /**
     * Header for compiling a MARKING_CORPUS avatar: structures a teacher's
     * rubrics, mark schemes, guidelines and PAST MARKED papers into reusable
     * marking-BEHAVIOUR pages (how marks are awarded, deductions, model-answer
     * shape, grade bands, comment phrasing) — inferring HOW the teacher marks,
     * not the question content. Ends at the source section; the caller appends
     * the marking materials + its output-format block.
     */
    static String markingHeader(String subjectLabel) {
        return """
                You are building a TUITION CENTRE'S MARKING STANDARD — a reusable guide to HOW
                THIS CENTRE MARKS a subject, learned from the teacher's rubrics, mark schemes,
                marking guidelines, and PAST MARKED papers.

                Subject: %s

                ## YOUR TASK
                Convert the marking materials below into structured MARKING-BEHAVIOUR pages —
                NOT topic/content pages. Each page captures ONE reusable marking RULE or PATTERN
                a grader applies, so an AI can draft feedback that matches this centre's standard.

                ## WHAT TO EXTRACT (create the pages the material supports)
                - "How marks are awarded" — method vs accuracy vs ECF (error-carried-forward),
                  what earns each mark, mark allocation per step.
                - "Common deductions & why" — the mistakes this teacher penalises, and how much.
                - "Model answer shape per question type" — the structure a full-mark answer takes.
                - "Grade band descriptors" — what distinguishes an A / B / C response.
                - "House style / comment phrasing" — how this teacher phrases feedback and praise.

                ## FROM MARKED PAPERS SPECIFICALLY
                Infer the marking BEHAVIOUR, not the question content: where marks were given or
                taken, the annotations, and the comment wording. Record the PATTERN (e.g. "awards
                the method mark even when the final answer is wrong"), never the specific question.

                ## CRITICAL RULES
                1. PRESERVE exact mark allocations, numbers, and rule thresholds.
                2. Each page covers ONE marking rule/pattern; markdown (## / - / **bold**).
                3. 150-400 words per page.

                ## EXAMPLE OUTPUT
                Each object MUST include a "context" field: a 1-2 sentence summary
                situating the rule ("This page covers <which marking rule> for <subject>") —
                it is prepended to the page when finding it later, so make it specific.
                [{"slug": "awarding-method-marks",
                  "title": "How Method Marks Are Awarded",
                  "content": "## Principle\\nAward the **method mark** when the correct approach is shown, even if the final answer is wrong (ECF applies).\\n\\n## In practice\\n- 1 mark for the correct formula\\n- 1 mark for correct substitution",
                  "context": "How method (M) marks are awarded, including ECF, in this centre's marking standard.",
                  "prerequisites": []}]

                ## MARKING MATERIALS TO COMPILE

                """.formatted(subjectLabel);
    }

    /**
     * Header for the WEAKNESS_PROFILE head — compiles a student's PERFORMANCE
     * signals (below) into structured pages modelling what THIS student
     * repeatedly gets wrong, so the tutor can target their gaps. NOT topic
     * pages (that's the notes head) and NOT marking rules (that's marking) —
     * per-student weakness pages the tutor grounds on.
     */
    static String weaknessHeader(String subjectLabel) {
        return """
                You are building a STUDENT'S WEAKNESS PROFILE — a private, per-student model of
                what THIS learner repeatedly struggles with in a subject, learned from their own
                performance signals (quiz results, self-reported confidence, low-mastery topics).

                Subject: %s

                ## YOUR TASK
                Convert the performance signals below into structured WEAKNESS pages — NOT topic
                explainers and NOT marking rules. Each page captures ONE weak area for this
                student, so an AI tutor can target practice and hedge on shaky ground.

                ## WHAT EACH PAGE CAPTURES
                - The topic + the SPECIFIC sub-skill that fails (e.g. "fractions — the division
                  step", not just "fractions").
                - EVIDENCE: recent wrong attempts / low mastery ratio / low confidence — cite the
                  numbers from the signals (e.g. "2/5 correct over 5 attempts; confidence LOW").
                - What's already STRONG nearby, so the tutor builds on it rather than re-teaching.
                - A short "how to help" cue (what kind of practice would close this gap).

                ## CRITICAL RULES
                1. Base every claim on the signals given — never invent weaknesses.
                2. One weak sub-skill per page; markdown (## / - / **bold**).
                3. A topic the student is STRONG at (high mastery, high confidence) gets NO page.
                4. 120-300 words per page.

                ## EXAMPLE OUTPUT
                Each object MUST include a "context" field: a 1-2 sentence summary situating the
                weakness ("This page covers <student's weak sub-skill> within <subject/topic>").
                [{"slug": "fractions-division-step",
                  "title": "Fractions — the division step",
                  "content": "## Where it breaks\\nStudent inverts-and-multiplies incorrectly.\\n\\n## Evidence\\n- 1/4 correct over 4 recent attempts\\n- Confidence self-reported **LOW**\\n\\n## Already strong\\n- Multiplying fractions (5/5 correct)\\n\\n## How to help\\nDrill 'keep-change-flip' with 3 worked examples, then 2 solo.",
                  "context": "This student's weak sub-skill: the division step in fractions, within %s.",
                  "prerequisites": []}]

                ## PERFORMANCE SIGNALS TO COMPILE

                """.formatted(subjectLabel, subjectLabel);
    }
}
