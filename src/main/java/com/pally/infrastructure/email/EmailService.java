package com.pally.infrastructure.email;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around JavaMailSender. Guards against blank password so that
 * tests and dev environments never attempt real SMTP delivery.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.from:noreply@memoly.app}")
    private String fromAddress;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    /**
     * Sends an HTML email. If MAIL_PASSWORD is blank (dev/test), logs a
     * warning and skips the send — no tests will fail because of mail config.
     */
    public void sendHtml(String to, String subject, String html) {
        if (mailPassword == null || mailPassword.isBlank()) {
            log.warn("[Email] MAIL_PASSWORD not configured — skipping email to={} subject='{}'",
                    to, subject);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
            log.info("[Email] Sent to={} subject='{}'", to, subject);
        } catch (MessagingException e) {
            log.error("[Email] Failed to send email to={} subject='{}': {}",
                    to, subject, e.getMessage());
        }
    }
}
