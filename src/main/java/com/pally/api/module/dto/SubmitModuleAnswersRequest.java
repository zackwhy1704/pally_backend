package com.pally.api.module.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;

/// Payload for {@code POST /api/v1/avatars/{id}/modules/{moduleId}/submit}.
///
/// @param submissions     per-item answers (itemId + response)
/// @param durationSeconds optional wall-clock seconds spent on this stage.
///                        Clamped server-side to [0, 3600]; null/missing → 0
///                        (legacy clients). Logged when a stage completes so
///                        the weekly minutes chart reflects module work.
public record SubmitModuleAnswersRequest(
        @NotEmpty List<Map<String, String>> submissions,
        Integer durationSeconds
) {}
