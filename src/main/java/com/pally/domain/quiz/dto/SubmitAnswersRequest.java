package com.pally.domain.quiz.dto;

import java.util.Map;

/**
 * Quiz answer submission payload.
 *
 * @param answers       questionId → selectedIndex
 * @param correctMap    questionId → correctIndex (held client-side from /quiz/daily)
 * @param topicMap      optional questionId → topic slug (powers weak-topic stats)
 * @param confidenceMap optional questionId → confidence (LOW/MEDIUM/HIGH).
 *                       When provided, the response includes a mastery matrix
 *                       (mastered / misconception / luckyGuess / knownGap).
 * @param durationSeconds optional wall-clock seconds the kid spent on this quiz.
 *                       Clamped server-side to [0, 3600]. Null/missing → 0 so
 *                       legacy clients stay backward compatible. Feeds the
 *                       "minutes studied this week" progress chart.
 */
public record SubmitAnswersRequest(
        Map<String, Integer> answers,
        Map<String, Integer> correctMap,
        Map<String, String> topicMap,
        Map<String, String> confidenceMap,
        Integer durationSeconds
) {}
