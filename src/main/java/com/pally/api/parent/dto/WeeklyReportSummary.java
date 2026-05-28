package com.pally.api.parent.dto;

import java.time.LocalDate;

/**
 * Compact row in the parent's weekly-report list. Detail comes from
 * {@code GET /api/v1/parent/reports/{weekId}}.
 */
public record WeeklyReportSummary(
        String weekId,         // "2026-W22"
        LocalDate startDate,   // Monday of the ISO week
        LocalDate endDate,     // Sunday of the ISO week
        int sessions,
        int minutes,
        int xpEarned
) {}
