package com.pally.api.teach.dto;

import java.util.List;

/**
 * Feedback after the student teaches a topic to the avatar.
 *
 * @param coveredConcepts  concepts the student mentioned correctly
 * @param missedConcepts   concepts the student left out
 * @param followUpQuestion one Socratic question targeting the largest gap,
 *                          or {@code null} when the student covered everything
 */
public record TeachResponse(
        int score,
        int totalConcepts,
        int xpEarned,
        List<String> coveredConcepts,
        List<String> missedConcepts,
        String followUpQuestion,
        String feedback
) {}
