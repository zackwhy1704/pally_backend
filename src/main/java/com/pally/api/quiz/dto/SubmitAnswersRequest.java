package com.pally.api.quiz.dto;

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
 */
public record SubmitAnswersRequest(
        Map<String, Integer> answers,
        Map<String, Integer> correctMap,
        Map<String, String> topicMap,
        Map<String, String> confidenceMap
) {}
