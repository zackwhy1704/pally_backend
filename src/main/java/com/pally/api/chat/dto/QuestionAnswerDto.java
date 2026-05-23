package com.pally.api.chat.dto;

import java.util.List;

public record QuestionAnswerDto(
        String questionId,
        String questionText,
        String answer,
        List<String> steps,
        String explanation
) {}
