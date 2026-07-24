package com.pally.domain.report;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.infrastructure.config.AdminEmailService;
import com.pally.infrastructure.email.EmailService;
import com.pally.shared.exception.AvatarNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Files a content report: verifies the avatar belongs to the caller, PERSISTS the report (the row
 * is the record of truth), then emails every admin BEST-EFFORT. A failed email must never fail the
 * report. Emailing (not a dashboard) is the review surface — a report that lands in an admin inbox
 * with the verbatim message text IS a human review path; a dashboard is deferred until volume warrants
 * it (trigger: reports exceed ~5/week or a second person triages). No dependency on a persisted
 * chat-message row — the report carries the text itself.
 *
 * <p>Reaches {@code EmailService}/{@code AdminEmailService} directly (same pattern as DemoLeadService);
 * persistence goes through the {@link ContentReportRepository} PORT (domain never imports JPA).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SubmitContentReportUseCase {

    private final ContentReportRepository reportRepository;
    private final AvatarRepository avatarRepository;
    private final EmailService emailService;
    private final AdminEmailService adminEmailService;

    public void submit(String avatarId, String userId, ContentReportReason reason,
                       String comment, String messageText, String clientMessageId) {
        // Ownership: the avatar must belong to the caller — same check chat itself uses. 404 (not
        // 403) so another user's avatar existence is not revealed.
        avatarRepository.findById(avatarId)
                .filter(a -> a.getUserId().equals(userId))
                .orElseThrow(() -> new AvatarNotFoundException(avatarId));

        // Persist FIRST — the DB row is the record of truth; email is best-effort below.
        ContentReport report = reportRepository.save(
                ContentReport.of(avatarId, userId, reason, comment, messageText, clientMessageId));
        log.info("[ContentReport] persisted id={} avatar={} reason={} user={}",
                report.id(), avatarId, reason, userId);

        notifyAdmins(report);
    }

    private void notifyAdmins(ContentReport report) {
        Set<String> recipients = adminEmailService.recipients();
        if (recipients.isEmpty()) {
            log.warn("[ContentReport] ADMIN_EMAILS not set — report id={} persisted but NOT emailed (no reviewer)",
                    report.id());
            return;
        }
        String subject = "⚠️ Content report: " + report.reason();
        String html = buildHtml(report);
        for (String to : recipients) {
            try {
                emailService.sendHtml(to, subject, html);
                log.info("[ContentReport] emailed report id={} to an admin", report.id());
            } catch (Exception e) {
                // Best-effort: a failed email must NOT fail the report (persist is the truth).
                log.warn("[ContentReport] admin email failed for report id={}: {}", report.id(), e.getMessage());
            }
        }
    }

    private String buildHtml(ContentReport r) {
        String comment = (r.comment() == null || r.comment().isBlank())
                ? "<em>(none)</em>" : escape(r.comment());
        return ("<h2>Content report</h2>"
                + "<p><strong>Reason:</strong> %s</p>"
                + "<p><strong>Reported Mochi message:</strong></p>"
                + "<blockquote style=\"border-left:3px solid #ccc;padding-left:12px;color:#333\">%s</blockquote>"
                + "<p><strong>Reporter comment:</strong> %s</p>"
                + "<hr><p style=\"color:#666;font-size:12px\">avatarId: %s<br>userId: %s<br>"
                + "clientMessageId: %s<br>at: %s (UTC)</p>")
                .formatted(r.reason(), escape(r.messageText()), comment,
                        escape(r.avatarId()), escape(r.userId()),
                        r.clientMessageId() == null ? "—" : escape(r.clientMessageId()),
                        r.createdAt());
    }

    /** Minimal HTML-escape so reported text can't inject markup into the admin email. */
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
