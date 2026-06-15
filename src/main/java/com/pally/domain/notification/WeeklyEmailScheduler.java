package com.pally.domain.notification;

import com.pally.domain.account.AccountType;

import com.pally.api.parent.dto.ParentDashboardResponse.SubjectMasteryDto;
import com.pally.domain.progress.WeeklyReportService;
import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.persistence.activity.ActivityLogJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Sends a weekly summary email to every PARENT account every Sunday at 8pm SGT.
 * Iterates children, builds a report for each child with activity, and sends one
 * combined email per parent.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyEmailScheduler {

    private final UserRepository userRepo;
    private final ActivityLogJpaRepository activityRepo;
    private final WeeklyReportService weeklyReportService;
    private final EmailService emailService;

    @Scheduled(cron = "0 0 20 * * SUN", zone = "Asia/Singapore")
    public void sendWeeklyReports() {
        log.info("[WeeklyEmail] Starting weekly parent report emails");
        // Find all PARENT accounts
        // No dedicated query — iterate all parents from the child-side
        List<User> parents = userRepo.findByAccountType(AccountType.PARENT);

        int sent = 0;
        for (User parent : parents) {
            try {
                sent += sendReportForParent(parent);
            } catch (Exception e) {
                log.error("[WeeklyEmail] Failed for parent={}: {}", parent.getId(), e.getMessage());
            }
        }
        log.info("[WeeklyEmail] Sent {} emails to {} parents", sent, parents.size());
    }

    int sendReportForParent(User parent) {
        if (parent.getEmail() == null || parent.getEmail().isBlank()) {
            return 0;
        }
        List<User> children = userRepo.findByParentId(parent.getId());
        if (children.isEmpty()) return 0;

        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        StringBuilder html = new StringBuilder();
        html.append("<html><body style='font-family:Arial,sans-serif;max-width:600px;margin:0 auto'>");
        html.append("<h2 style='color:#7042ED'>Apalchi Weekly Report</h2>");

        boolean anyActivity = false;
        for (User child : children) {
            int minutes = orZero(activityRepo.sumMinutesBetween(
                    child.getId(), weekAgo, Instant.now()));
            if (minutes == 0) continue;
            anyActivity = true;

            int sessions = orZero(activityRepo.countSince(child.getId(), weekAgo));
            int xp = orZero(activityRepo.sumXpSince(child.getId(), weekAgo));

            String childName = child.getChildName() != null
                    ? child.getChildName()
                    : (child.getDisplayName() != null ? child.getDisplayName() : "Your child");

            html.append("<div style='margin-bottom:24px;padding:16px;border:1px solid #E0DAF0;border-radius:12px'>");
            html.append("<h3 style='color:#1F1733;margin:0'>").append(escapeHtml(childName)).append("</h3>");
            html.append("<table style='margin-top:8px'>");
            html.append("<tr><td style='padding:4px 12px 4px 0;color:#6B618A'>Minutes studied:</td><td><b>").append(minutes).append("</b></td></tr>");
            html.append("<tr><td style='padding:4px 12px 4px 0;color:#6B618A'>Sessions:</td><td><b>").append(sessions).append("</b></td></tr>");
            html.append("<tr><td style='padding:4px 12px 4px 0;color:#6B618A'>XP earned:</td><td><b>+").append(xp).append("</b></td></tr>");
            html.append("<tr><td style='padding:4px 12px 4px 0;color:#6B618A'>Streak:</td><td><b>").append(child.getStreakDays()).append(" days</b></td></tr>");
            html.append("</table>");

            List<SubjectMasteryDto> subjects = weeklyReportService.computeSubjectMastery(child.getId());
            if (!subjects.isEmpty()) {
                html.append("<p style='margin:12px 0 4px;color:#6B618A;font-size:13px'>Subject mastery:</p>");
                for (SubjectMasteryDto s : subjects) {
                    int pct = (int) Math.round(s.mastery() * 100);
                    html.append("<div style='margin:2px 0'>")
                            .append(s.subject().toLowerCase(Locale.ROOT))
                            .append(": <b>").append(pct).append("%</b></div>");
                }
            }
            html.append("</div>");
        }

        if (!anyActivity) return 0;

        html.append("<p style='color:#6B618A;font-size:13px'>Keep it up! Open Apalchi to keep learning.</p>");
        html.append("</body></html>");

        emailService.sendHtml(parent.getEmail(),
                "Apalchi Weekly Report", html.toString());
        return 1;
    }

    private int orZero(Integer i) {
        return i == null ? 0 : i;
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
