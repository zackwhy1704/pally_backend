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
        int screenTimeMinutes
) {
    public record SubjectMasteryDto(String subject, double mastery) {}
    public record WeakAreaDto(String topic, double mastery) {}
}
