package com.pally.api.teach.dto;

/// Payload for {@code POST /api/v1/avatars/{id}/teach}.
public record TeachRequest(String topicSlug, String explanation) {}
