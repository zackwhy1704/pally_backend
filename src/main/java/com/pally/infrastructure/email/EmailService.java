package com.pally.infrastructure.email;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Sends transactional email via the Resend HTTP API (https://api.resend.com).
 *
 * <p>Cloud platforms like Railway block outbound SMTP ports (25/465/587) to
 * curb spam, so SMTP delivery hangs. Resend's HTTPS API (port 443) is never
 * blocked and is the reliable path. The Resend API key is read from
 * {@code MAIL_PASSWORD} (the same {@code re_...} value), and the verified
 * sender from {@code MAIL_FROM}.
 *
 * <p>If the API key is blank (dev/test), all sends are no-ops with a warning —
 * safe for environments without email configured.
 */
@Service
@Slf4j
public class EmailService {

    private static final String RESEND_API_URL = "https://api.resend.com/emails";

    private final WebClient webClient;

    @Value("${spring.mail.from:noreply@apalchi.com}")
    private String fromAddress;

    /// The Resend API key (re_...). Reuses the MAIL_PASSWORD slot so existing
    /// Railway config keeps working without a new variable.
    @Value("${spring.mail.password:}")
    private String apiKey;

    private boolean enabled;

    public EmailService(WebClient webClient) {
        this.webClient = webClient;
    }

    @PostConstruct
    void init() {
        enabled = apiKey != null && !apiKey.isBlank();
        if (!enabled) {
            log.warn("[Email] Resend API key (MAIL_PASSWORD) not set — email disabled");
        } else {
            log.info("[Email] Resend HTTP API configured, from={}", fromAddress);
        }
    }

    /** Whether email sending is configured and active. */
    public boolean isConfigured() {
        return enabled;
    }

    /** The configured from-address. */
    public String getFrom() {
        return fromAddress;
    }

    public void sendHtml(String to, String subject, String html) {
        if (!enabled) {
            log.debug("[Email] Disabled — skipping email to={} subject='{}'", to, subject);
            return;
        }
        try {
            Map<String, Object> body = Map.of(
                    "from", fromAddress,
                    "to", List.of(to),
                    "subject", subject,
                    "html", html);
            String response = webClient.post()
                    .uri(RESEND_API_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(15))
                    .block();
            log.info("[Email] Sent to={} subject='{}' resp={}", to, subject, response);
        } catch (Exception e) {
            log.error("[Email] Failed to send email to={} subject='{}': {}",
                    to, subject, e.getMessage());
        }
    }
}
