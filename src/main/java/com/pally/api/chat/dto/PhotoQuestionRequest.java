package com.pally.api.chat.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record PhotoQuestionRequest(
        @NotEmpty(message = "questions must not be empty") List<String> questions,
        List<String> wikiPageIds
) {}
