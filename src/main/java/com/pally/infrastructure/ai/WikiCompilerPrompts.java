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
                [{"slug": "awarding-method-marks",
                  "title": "How Method Marks Are Awarded",
                  "content": "## Principle\\nAward the **method mark** when the correct approach is shown, even if the final answer is wrong (ECF applies).\\n\\n## In practice\\n- 1 mark for the correct formula\\n- 1 mark for correct substitution",
                  "prerequisites": []}]

                ## MARKING MATERIALS TO COMPILE

                """.formatted(subjectLabel);
    }
}
