package com.pally.api.parent.dto;

import java.util.List;

public record ParentDashboardResponse(
        int sessionsThisWeek,
        int minutesThisWeek,
        int xpThisWeek,
        int level,
        int streakDays,
        List<SubjectMasteryDto> subjects,
        List<Integer> weekMinutes,
        List<WeakAreaDto> weakAreas,
        boolean screenTimeEnabled,
        int screenTimeMinutes,
        // R8 — topics flagged for review by the quiz feedback loop
        // (aggregated across the parent's avatars).
        List<String> reviewTopics
) {
    public record SubjectMasteryDto(String subject, double mastery) {}
    public record WeakAreaDto(String topic, double mastery) {}
}
