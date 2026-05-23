package com.pally.api.quiz.dto;

import com.pally.domain.quiz.QuizQuestion;

import java.util.List;

public record QuizQuestionResponse(
        String id,
        String question,
        List<String> options,
        String sourcePageSlug
) {
    public static QuizQuestionResponse from(QuizQuestion q) {
        return new QuizQuestionResponse(q.id(), q.question(), q.options(), q.sourcePageSlug());
    }
}
