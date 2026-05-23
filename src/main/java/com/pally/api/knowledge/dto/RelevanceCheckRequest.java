package com.pally.api.knowledge.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for a manual relevance check.
 *
 * @param contentSample text sample to evaluate for relevance to the avatar's subject
 */
public record RelevanceCheckRequest(
        @NotBlank(message = "Content sample must not be blank") String contentSample
) {}
