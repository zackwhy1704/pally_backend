package com.pally.domain.knowledge.dto;

public record RelevanceCheckResponse(double score, String reason, boolean isRelevant) {}
