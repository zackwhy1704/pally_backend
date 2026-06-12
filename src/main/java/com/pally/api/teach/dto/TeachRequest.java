package com.pally.api.teach.dto;

/// Payload for {@code POST /api/v1/avatars/{id}/teach}.
///
/// @param durationSeconds optional wall-clock seconds spent on this teach
///                        session. Clamped server-side to [0, 3600]; null →
///                        0 (legacy clients). Feeds the weekly minutes chart.
public record TeachRequest(String topicSlug, String explanation,
                           Integer durationSeconds) {}
