package com.pally.api.chat.dto;

import java.util.List;

public record PhotoQuestionResponse(
        List<QuestionAnswerDto> answers,
        int xpEarned,
        String sourceWikiPage
) {}
