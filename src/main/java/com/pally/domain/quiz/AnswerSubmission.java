package com.pally.domain.quiz;

import java.util.Map;

public record AnswerSubmission(
        String avatarId,
        String userId,
        Map<String, Integer> answers  // questionId → answerIndex
) {}
