package com.pally.api.quiz.dto;

import java.util.Map;

public record SubmitAnswersRequest(
        Map<String, Integer> answers,
        Map<String, Integer> correctMap
) {}
