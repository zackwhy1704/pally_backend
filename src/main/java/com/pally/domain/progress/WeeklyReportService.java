package com.pally.domain.progress;

import com.pally.domain.parent.dto.ParentDashboardResponse.SubjectMasteryDto;
import com.pally.domain.parent.dto.ParentDashboardResponse.WeakAreaDto;
import com.pally.domain.parent.dto.WeeklyReportDetail;
import com.pally.domain.parent.dto.WeeklyReportSummary;
import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import com.pally.infrastructure.persistence.quiz.QuizQuestionResultJpaRepository;
import com.pally.shared.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Shared service for computing weekly reports. Used by both ParentController
 * (self-viewing) and ParentChildController (parent viewing child's data).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyReportService {

    private static final int WEEKS_TO_LIST = 4;

    private final ActivityLogJpaRepository activityRepo;
    private final ActivityLogService activityLogService;
    private final QuizQuestionResultJpaRepository quizResultRepo;
    private final AvatarRepository avatarRepository;

    public List<WeeklyReportSummary> listReports(String userId) {
        List<WeeklyReportSummary> reports = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < WEEKS_TO_LIST; i++) {
            LocalDate weekStart = isoWeekStart(today.minusWeeks(i));
            LocalDate weekEnd = weekStart.plusDays(6);
            reports.add(buildSummary(userId, weekStart, weekEnd));
        }
        return reports;
    }

    public WeeklyReportDetail getReport(String userId, String weekId) {
        LocalDate weekStart = parseWeekId(weekId);
        LocalDate weekEnd = weekStart.plusDays(6);
        Instant from = weekStart.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = weekEnd.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        int sessions = orZero(activityRepo.countBetween(userId, from, to));
        int minutes = orZero(activityRepo.sumMinutesBetween(userId, from, to));
        int xp = orZero(activityRepo.sumXpBetween(userId, from, to));
        List<Integer> dailyMinutes = activityLogService.minutesPerDayBetween(
                userId, weekStart, weekEnd);

        List<SubjectMasteryDto> subjects = computeSubjectMastery(userId);
        List<WeakAreaDto> weakAreas = quizResultRepo
                .findWeakestTopics(userId, 5).stream()
                .map(r -> new WeakAreaDto((String) r[0],
                        ((Number) r[1]).doubleValue()))
                .toList();

        String headline = buildHeadline(sessions, minutes, xp);
        String narrative = buildNarrative(sessions, minutes, xp, subjects, weakAreas);

        return new WeeklyReportDetail(
                weekId, weekStart, weekEnd,
                sessions, minutes, xp,
                dailyMinutes, subjects, weakAreas,
                headline, narrative);
    }

    public List<SubjectMasteryDto> computeSubjectMastery(String userId) {
        List<Avatar> avatars = avatarRepository.findByUserId(userId);
        List<SubjectMasteryDto> result = new ArrayList<>();
        for (Avatar avatar : avatars) {
            List<Object[]> rows = quizResultRepo.findWeakestTopicsByAvatar(
                    userId, avatar.getId(), 50);
            double mastery = rows.stream()
                    .mapToDouble(r -> ((Number) r[1]).doubleValue())
                    .average()
                    .orElse(0.0);
            result.add(new SubjectMasteryDto(
                    avatar.getSubject().name(), mastery));
        }
        return result;
    }

    public WeeklyReportSummary buildSummary(String userId, LocalDate start, LocalDate end) {
        Instant from = start.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        return new WeeklyReportSummary(
                isoWeekId(start), start, end,
                orZero(activityRepo.countBetween(userId, from, to)),
                orZero(activityRepo.sumMinutesBetween(userId, from, to)),
                orZero(activityRepo.sumXpBetween(userId, from, to)));
    }

    public String buildHeadline(int sessions, int minutes, int xp) {
        if (sessions == 0) return "No activity this week";
        if (minutes >= 60) {
            return "Solid week — %d min across %d session%s"
                    .formatted(minutes, sessions, sessions == 1 ? "" : "s");
        }
        return "%d session%s, %d min total"
                .formatted(sessions, sessions == 1 ? "" : "s", minutes);
    }

    public String buildNarrative(int sessions, int minutes, int xp,
                                  List<SubjectMasteryDto> subjects,
                                  List<WeakAreaDto> weakAreas) {
        if (sessions == 0) {
            return "Your child didn't open Apalchi this week. "
                    + "A short daily reminder can help re-engage.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("This week your child earned %d XP across %d minutes of focused practice."
                .formatted(xp, minutes));
        if (!subjects.isEmpty()) {
            var top = subjects.stream()
                    .max((a, b) -> Double.compare(a.mastery(), b.mastery()))
                    .orElse(null);
            if (top != null && top.mastery() > 0) {
                sb.append(" Strongest subject: %s (%.0f%%)."
                        .formatted(top.subject().toLowerCase(Locale.ROOT),
                                top.mastery() * 100));
            }
        }
        if (!weakAreas.isEmpty()) {
            sb.append(" Topic to focus on next week: ")
                    .append(weakAreas.get(0).topic()).append('.');
        }
        return sb.toString();
    }

    // ── Date helpers ──────────────────────────────────────────────────────

    public LocalDate isoWeekStart(LocalDate date) {
        WeekFields wf = WeekFields.ISO;
        int dow = date.get(wf.dayOfWeek());
        return date.minusDays(dow - 1);
    }

    public String isoWeekId(LocalDate weekStart) {
        WeekFields wf = WeekFields.ISO;
        int year = weekStart.get(wf.weekBasedYear());
        int week = weekStart.get(wf.weekOfWeekBasedYear());
        return String.format("%04d-W%02d", year, week);
    }

    public LocalDate parseWeekId(String weekId) {
        int dash = weekId.indexOf('-');
        if (dash < 0 || weekId.length() < 7
                || weekId.charAt(dash + 1) != 'W') {
            throw new DateTimeParseException("bad format", weekId, 0);
        }
        int year = Integer.parseInt(weekId.substring(0, dash));
        int week = Integer.parseInt(weekId.substring(dash + 2));
        LocalDate jan4 = LocalDate.of(year, 1, 4);
        LocalDate jan4Monday = isoWeekStart(jan4);
        return jan4Monday.plusWeeks(week - 1L);
    }

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }
}
