package com.pally.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Single source of truth for admin-email membership.
 *
 * <p>Reads {@code ADMIN_EMAILS} from the Railway environment at startup.
 * The check at call-time is against this in-memory set — the DB {@code role}
 * column is intentionally NOT consulted. This means:
 * <ul>
 *   <li>No database manipulation can grant admin access.</li>
 *   <li>Adding / removing an email from {@code ADMIN_EMAILS} + redeploying
 *       is the only way to change admin status.</li>
 * </ul>
 *
 * <p>Format: comma or semicolon separated, case-insensitive.
 * {@code ADMIN_EMAILS=alice@example.com,bob@example.com}
 */
@Service
@Slf4j
public class AdminEmailService {

    private final Set<String> adminEmails;

    public AdminEmailService(
            @Value("${pally.admin-emails:${ADMIN_EMAILS:}}") String csv) {
        this.adminEmails = Arrays.stream(csv.split("[,;]"))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        if (adminEmails.isEmpty()) {
            log.warn("[AdminEmailService] ADMIN_EMAILS is not set — no admin users will have Tuition tab access");
        } else {
            log.info("[AdminEmailService] Loaded {} admin email(s)", adminEmails.size());
        }
    }

    /**
     * Returns true iff {@code email} appears in the {@code ADMIN_EMAILS}
     * environment variable. Case-insensitive. Never reads the DB.
     */
    public boolean isAdmin(String email) {
        if (email == null || email.isBlank()) return false;
        return adminEmails.contains(email.trim().toLowerCase());
    }

    /**
     * The configured admin recipient addresses (unmodifiable; empty when {@code ADMIN_EMAILS}
     * is unset). Used to fan out operational notifications (e.g. content reports) to admins.
     */
    public Set<String> recipients() {
        return adminEmails;
    }
}
