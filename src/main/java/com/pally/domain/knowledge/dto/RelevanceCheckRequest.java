package com.pally.domain.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

public record RelevanceCheckRequest(
        @NotBlank(message = "Content sample must not be blank") String contentSample
) {}
