package com.pally.api.parent.dto;

import com.pally.api.parent.dto.ParentDashboardResponse.SubjectMasteryDto;
import com.pally.api.parent.dto.ParentDashboardResponse.WeakAreaDto;

import java.time.LocalDate;
import java.util.List;

/**
 * Full weekly report sent to {@code GET /api/v1/parent/reports/{weekId}}.
 *
 * <p>{@code dailyMinutes} is always 7 entries, Monday → Sunday, even when
 * the week is partially elapsed (future days = 0).
 */
public record WeeklyReportDetail(
        String weekId,
        LocalDate startDate,
        LocalDate endDate,
        int sessions,
        int minutes,
        int xpEarned,
        List<Integer> dailyMinutes,
        List<SubjectMasteryDto> subjects,
        List<WeakAreaDto> weakAreas,
        String headline,
        String narrative
) {}
