package com.pally.infrastructure.email;

import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around JavaMailSender. If MAIL_PASSWORD is blank, all sends
 * are no-ops — safe for dev, test, and Railway without SMTP configured.
 */
@Service
@Slf4j
public class EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.from:noreply@apalchi.com}")
    private String fromAddress;

    @Value("${spring.mail.password:}")
    private String mailPassword;

    private boolean enabled;

    public EmailService(@Autowired(required = false) JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @PostConstruct
    void init() {
        enabled = mailSender != null
                && mailPassword != null
                && !mailPassword.isBlank();
        if (!enabled) {
            log.warn("[Email] Mail not configured — email sending disabled");
        } else {
            log.info("[Email] Mail configured, from={}", fromAddress);
        }
    }

    public void sendHtml(String to, String subject, String html) {
        if (!enabled) {
            log.debug("[Email] Disabled — skipping email to={} subject='{}'", to, subject);
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
