package com.pally.api.progress.dto;

import com.pally.domain.progress.ProgressSummary;

import java.util.List;

/**
 * Progress dashboard payload. {@code xpIntoLevel}/{@code xpSpanForLevel} let
 * the client render the bar without re-deriving the leveling curve — keeps
 * frontend math trivial and prevents drift.
 */
public record ProgressResponse(
        int xp,
        int level,
        int xpToNextLevel,
        int xpIntoLevel,
        int xpSpanForLevel,
        int maxLevel,
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
                ProgressSummary.xpIntoLevel(s.xp()),
                ProgressSummary.xpSpanForLevel(s.xp()),
                ProgressSummary.MAX_LEVEL,
                s.streakDays(), s.stars(),
                s.totalFlashcards(), s.dueFlashcards(),
                s.totalQuizzesTaken(), s.averageScore(),
                s.weekMinutes() != null ? s.weekMinutes() : List.of(),
                s.badges() != null ? s.badges() : List.of()
        );
    }
}
