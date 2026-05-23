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
) {}
