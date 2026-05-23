package com.pally.api.knowledge.dto;

/**
 * Response body for a relevance check result.
 *
 * @param score      relevance score in range [0.0, 1.0]
 * @param reason     short explanation from the AI model
 * @param isRelevant {@code true} if score meets the relevance threshold
 */
public record RelevanceCheckResponse(double score, String reason, boolean isRelevant) {}
