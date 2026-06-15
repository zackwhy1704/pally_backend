package com.pally.domain.quiz.dto;

import com.pally.domain.quiz.QuizQuestion;

import java.util.List;

public record QuizQuestionResponse(
        String id,
        String question,
        List<String> options,
        String sourcePageSlug,
        // correctIndex must be sent to the client so answers can be submitted via
        // POST /quiz/answers. SubmitAnswersRequest.correctMap holds questionId→correctIndex
        // which the client provides back. Without this field the client can't submit answers.
        int correctIndex,
        String explanation
) {
    public static QuizQuestionResponse from(QuizQuestion q) {
        return new QuizQuestionResponse(
                q.id(), q.question(), q.options(), q.sourcePageSlug(),
                q.correctIndex(), q.explanation());
    }
}
