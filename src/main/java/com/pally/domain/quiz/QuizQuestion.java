package com.pally.domain.quiz;

import java.util.List;

public record QuizQuestion(
        String id,
        String avatarId,
        String question,
        List<String> options,
        int correctIndex,
        String sourcePageSlug,
        String explanation
) {
    /** Returns a copy with a corrected correctIndex — used by quiz verification. */
    public QuizQuestion withCorrectIndex(int newIndex) {
        return new QuizQuestion(id, avatarId, question, options, newIndex,
                sourcePageSlug, explanation);
    }
}
