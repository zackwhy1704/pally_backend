package com.pally.domain.quiz;

import java.util.List;

public record QuizQuestion(
        String id,
        String avatarId,
        String question,
        List<String> options,
        int correctIndex,
        String sourcePageSlug,
        String explanation,
        // Provenance metadata (SAFE — never reveals the answer): the source page title,
        // and why this question was chosen ("WEAK_TOPIC:{concept}" when the weak-first
        // picker selected it, else null). Appended at the end so positional constructors
        // stay stable.
        String sourcePageTitle,
        String selectionReason
) {
    /** Returns a copy with a corrected correctIndex — used by quiz verification. */
    public QuizQuestion withCorrectIndex(int newIndex) {
        return new QuizQuestion(id, avatarId, question, options, newIndex,
                sourcePageSlug, explanation, sourcePageTitle, selectionReason);
    }

    /** Returns a copy tagged with a selection reason (weak-first provenance). */
    public QuizQuestion withSelectionReason(String reason) {
        return new QuizQuestion(id, avatarId, question, options, correctIndex,
                sourcePageSlug, explanation, sourcePageTitle, reason);
    }
}
