package com.pally.infrastructure.ai;

import com.pally.infrastructure.email.EmailService;
import com.pally.infrastructure.persistence.safety.ChatSafetyFlagJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Sends a safety alert email when a user accumulates {@value #FLAG_ALERT_THRESHOLD}
 * or more moderation flags within a 24-hour window. Each flag that crosses or
 * re-crosses the threshold triggers an email so the admin doesn't miss escalations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SafetyAlertService {

    static final int FLAG_ALERT_THRESHOLD = 3;

    private final ChatSafetyFlagJpaRepository flagRepo;
    private final EmailService emailService;

    @Value("${pally.safety-alert-email:zhengyi1704@gmail.com}")
    private String alertEmail;

    /**
     * Checks the 24-hour flag count for {@code userId}; sends an alert if it
     * has reached (or passed) the threshold. Call this AFTER the flag has been
     * written to the DB by {@link ModerationService}.
     *
     * <p>This method is intentionally synchronous and safe to call from any thread.
     * Never call it on a Project Reactor scheduler thread — submit it via
     * {@code CompletableFuture.runAsync()} instead.
     */
    public void checkAndAlert(String userId, String avatarId, String messageId) {
        try {
            Instant since = Instant.now().minus(24, ChronoUnit.HOURS);
            long count = flagRepo.countByChildUserIdAndCreatedAtAfter(userId, since);

            if (count >= FLAG_ALERT_THRESHOLD) {
                log.warn("[Safety] User={} has {} flags in last 24h — sending alert", userId, count);
                sendAlert(userId, avatarId, messageId, count);
            }
        } catch (Exception e) {
            log.error("[Safety] Alert check failed for user={}: {}", userId, e.getMessage());
        }
    }

    private void sendAlert(String userId, String avatarId, String messageId, long count) {
        String subject = "[Apalchi Safety] " + count + " moderation flags in 24h — user " + userId;
        String html = """
                <h2>Safety Alert</h2>
                <p>User <strong>%s</strong> has accumulated <strong>%d</strong> moderation flags
                in the last 24 hours.</p>
                <table style="border-collapse:collapse;font-family:sans-serif;font-size:14px">
                  <tr><td style="padding:4px 8px;font-weight:bold">User ID</td>
                      <td style="padding:4px 8px">%s</td></tr>
                  <tr><td style="padding:4px 8px;font-weight:bold">Avatar ID</td>
                      <td style="padding:4px 8px">%s</td></tr>
                  <tr><td style="padding:4px 8px;font-weight:bold">Last message ID</td>
                      <td style="padding:4px 8px">%s</td></tr>
                  <tr><td style="padding:4px 8px;font-weight:bold">Flags (24h)</td>
                      <td style="padding:4px 8px">%d</td></tr>
                </table>
                <p style="margin-top:16px">Review the <code>chat_safety_flags</code> table for details.</p>
                """.formatted(userId, count, userId, avatarId, messageId, count);

        emailService.sendHtml(alertEmail, subject, html);
    }
}
