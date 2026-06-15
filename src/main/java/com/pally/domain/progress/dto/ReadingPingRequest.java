package com.pally.domain.progress.dto;

/// Payload for {@code POST /api/v1/progress/reading}.
///
/// Lets the mobile client report time the kid spent reading wiki/lesson
/// content so the "minutes studied this week" chart includes passive study,
/// not just quizzes and modules.
///
/// @param avatarId        the avatar whose content was being read
/// @param durationSeconds wall-clock seconds spent reading. Clamped
///                        server-side to [0, 3600]; null/missing → 0.
public record ReadingPingRequest(String avatarId, Integer durationSeconds) {}
