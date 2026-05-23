package com.pally.domain.quiz;

public record QuizResult(
        String sessionId,
        int score,
        int total,
        int xpEarned,
        int starsEarned
) {}
