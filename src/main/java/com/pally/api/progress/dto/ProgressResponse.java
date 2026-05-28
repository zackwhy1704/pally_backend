package com.pally.api.progress.dto;

import com.pally.domain.progress.ProgressSummary;

import java.util.List;

public record ProgressResponse(
        int xp,
        int level,
        int xpToNextLevel,
        int streakDays,
        int stars,
        int totalFlashcards,
        int dueFlashcards,
        int totalQuizzesTaken,
        int averageScore,
        List<Integer> weekMinutes,
        List<String> badges
) {
    public static ProgressResponse from(ProgressSummary s) {
        return new ProgressResponse(
                s.xp(), s.level(), s.xpToNextLevel(),
                s.streakDays(), s.stars(),
                s.totalFlashcards(), s.dueFlashcards(),
                s.totalQuizzesTaken(), s.averageScore(),
                s.weekMinutes() != null ? s.weekMinutes() : List.of(),
                s.badges() != null ? s.badges() : List.of()
        );
    }
}
