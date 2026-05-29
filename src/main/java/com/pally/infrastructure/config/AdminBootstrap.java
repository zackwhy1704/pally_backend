package com.pally.infrastructure.config;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

/**
 * Idempotent admin promotion on startup. Set {@code ADMIN_EMAILS} (comma-
 * separated) on Railway to grant ADMIN role to known accounts without a
 * manual SQL edit — closes the "how do I become admin" gap left by V36's
 * fail-closed default of role='USER'. Re-runs on every boot; no-op for
 * already-promoted users.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap {

    @Value("${pally.admin-emails:${ADMIN_EMAILS:}}")
    private String adminEmailsCsv;

    @Bean
    public ApplicationRunner promoteAdminsRunner(UserJpaRepository userRepo) {
        return args -> promote(userRepo);
    }

    @Transactional
    protected void promote(UserJpaRepository userRepo) {
        if (adminEmailsCsv == null || adminEmailsCsv.isBlank()) {
            return;
        }
        List<String> emails = Arrays.stream(adminEmailsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        int promoted = 0;
        for (String email : emails) {
            UserJpaEntity user = userRepo.findByEmail(email).orElse(null);
            if (user == null) {
                // Don't auto-create — promotion happens after the human
                // self-registers, to avoid an admin account with no
                // password set.
                log.warn("[AdminBootstrap] ADMIN_EMAILS entry not registered: <masked>");
                continue;
            }
            if (!"ADMIN".equals(user.getRole())) {
                user.setRole("ADMIN");
                userRepo.save(user);
                promoted++;
                log.info("[AdminBootstrap] Promoted user id={} to ADMIN",
                        user.getId());
            }
        }
        if (promoted > 0) {
            log.info("[AdminBootstrap] promoted {} new admin(s) this boot", promoted);
        }
    }
}
