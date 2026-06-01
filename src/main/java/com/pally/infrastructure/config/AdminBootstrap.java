package com.pally.infrastructure.config;

import com.pally.domain.subscription.PremiumService;
import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

/**
 * Idempotent admin promotion on startup.
 *
 * <p>Set {@code ADMIN_EMAILS} in Railway (comma OR semicolon separated) to:
 * <ol>
 *   <li>Promote the account to role=ADMIN.</li>
 *   <li>Grant lifetime Premium (plan=admin, status=active, expires 2099)
 *       so admins always have full access without a Stripe subscription.</li>
 * </ol>
 *
 * <p>Re-runs on every boot; safe to run multiple times (idempotent).
 * Accounts that aren't registered yet are skipped — promotion happens after
 * the human self-registers so there's no account with no password.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrap {

    @Value("${pally.admin-emails:${ADMIN_EMAILS:}}")
    private String adminEmailsCsv;

    private final SubscriptionJpaRepository subRepo;
    private final PremiumService premiumService;

    @Bean
    public ApplicationRunner promoteAdminsRunner(UserJpaRepository userRepo) {
        return args -> promote(userRepo);
    }

    @Transactional
    protected void promote(UserJpaRepository userRepo) {
        if (adminEmailsCsv == null || adminEmailsCsv.isBlank()) {
            return;
        }
        // Accept both comma and semicolon as separators (Railway env var convenience)
        List<String> emails = Arrays.stream(adminEmailsCsv.split("[,;]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        int promoted = 0;
        for (String email : emails) {
            UserJpaEntity user = userRepo.findByEmail(email).orElse(null);
            if (user == null) {
                log.warn("[AdminBootstrap] ADMIN_EMAILS entry not registered: <masked>");
                continue;
            }
            boolean changed = false;
            // 1. Role promotion
            if (!"ADMIN".equals(user.getRole())) {
                user.setRole("ADMIN");
                changed = true;
                log.info("[AdminBootstrap] Promoted id={} to ADMIN", user.getId());
            }
            // 2. Lifetime premium — upsert the subscription row
            grantAdminPremium(user.getId());
            // 3. Convert any active trial so the banner doesn't nag
            if ("ACTIVE".equals(user.getTrialStatus())) {
                user.setTrialStatus("CONVERTED");
                changed = true;
            }
            if (changed) {
                userRepo.save(user);
                promoted++;
            }
        }
        if (promoted > 0) {
            log.info("[AdminBootstrap] processed {} admin account(s)", promoted);
        }
    }

    private void grantAdminPremium(String userId) {
        Instant forever = Instant.now().plus(365 * 50, ChronoUnit.DAYS); // ~2075
        subRepo.findById(userId).ifPresentOrElse(
            sub -> {
                if (!"active".equals(sub.getStatus()) || !"admin".equals(sub.getPlan())) {
                    sub.setStatus("active");
                    sub.setPlan("admin");
                    sub.setCurrentPeriodEnd(forever);
                    sub.setUpdatedAt(Instant.now());
                    subRepo.save(sub);
                    premiumService.evictEntitlement(userId);
                    log.info("[AdminBootstrap] Updated premium for admin id={}", userId);
                }
            },
            () -> {
                SubscriptionJpaEntity sub = new SubscriptionJpaEntity();
                sub.setUserId(userId);
                sub.setStripeCustomerId("admin_bypass");
                sub.setStripeSubscriptionId("admin_bypass");
                sub.setPlan("admin");
                sub.setStatus("active");
                sub.setCurrentPeriodEnd(forever);
                sub.setUpdatedAt(Instant.now());
                subRepo.save(sub);
                premiumService.evictEntitlement(userId);
                log.info("[AdminBootstrap] Granted premium to admin id={}", userId);
            }
        );
    }
}
