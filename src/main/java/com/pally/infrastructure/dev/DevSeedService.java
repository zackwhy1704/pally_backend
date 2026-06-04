package com.pally.infrastructure.dev;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaEntity;
import com.pally.infrastructure.persistence.subscription.SubscriptionJpaRepository;
import com.pally.shared.util.IdGenerator;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Dev-profile-only seed service. Creates one test account per subscription
 * tier so developers can exercise every entitlement path without Stripe.
 *
 * <p>This bean is <b>completely inert in production</b>: the {@code @Profile("dev")}
 * annotation means Spring never instantiates it outside the dev profile.
 *
 * <p>All seeded accounts use the password {@code DevTest99!}.
 * Idempotent — skips rows that already exist.
 */
@Service
@Profile("dev")
@RequiredArgsConstructor
@Slf4j
public class DevSeedService {

    private static final String DEV_PASSWORD = "DevTest99!";

    // Fixed encoded hash of "DevTest99!" with strength 10 — pre-computed so
    // startup doesn't re-encode on every boot. Only valid in dev.
    private static final String DEV_PASSWORD_HASH =
            "$2a$10$devXXXXXXXXXXXXXXXXXXuDevSeedHashPlaceholderForDevOnly1";

    private record SeedAccount(
            String email,
            String plan,   // null = no subscription row (FREE/SPARK tier)
            int childCount // number of child accounts to link (only for FAMILY/CENTRE)
    ) {}

    private static final List<SeedAccount> SEED_ACCOUNTS = List.of(
            new SeedAccount("spark@dev.pally",  null,              0),
            new SeedAccount("pro@dev.pally",    "pro_monthly",     0),
            new SeedAccount("max@dev.pally",    "max_monthly",     0),
            new SeedAccount("family@dev.pally", "family_monthly",  2),
            new SeedAccount("centre@dev.pally", "centre_monthly",  3)
    );

    private final UserJpaRepository userRepo;
    private final SubscriptionJpaRepository subRepo;

    @PostConstruct
    @Transactional
    public void seed() {
        int created = 0;
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        String hash = encoder.encode(DEV_PASSWORD);

        for (SeedAccount account : SEED_ACCOUNTS) {
            if (userRepo.findByEmail(account.email()).isPresent()) {
                continue; // already seeded — idempotent
            }

            UserJpaEntity user = new UserJpaEntity();
            user.setId(IdGenerator.newId());
            user.setEmail(account.email());
            user.setDisplayName(account.email().split("@")[0]);
            user.setPasswordHash(hash);
            user.setLevel(1);
            user.setCreatedAt(Instant.now());
            user.setTrialStatus("NONE");
            userRepo.save(user);

            // Create subscription row for paid tiers
            if (account.plan() != null) {
                SubscriptionJpaEntity sub = new SubscriptionJpaEntity();
                sub.setUserId(user.getId());
                sub.setPlan(account.plan());
                sub.setStatus("active");
                sub.setCurrentPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
                sub.setCreatedAt(Instant.now());
                sub.setUpdatedAt(Instant.now());
                subRepo.save(sub);
            }

            // Create and link child accounts for FAMILY/CENTRE tiers
            for (int i = 1; i <= account.childCount(); i++) {
                String childEmail = account.email().replace("@dev.pally",
                        "-child" + i + "@dev.pally");
                if (userRepo.findByEmail(childEmail).isPresent()) continue;

                UserJpaEntity child = new UserJpaEntity();
                child.setId(IdGenerator.newId());
                child.setEmail(childEmail);
                child.setDisplayName("Child " + i + " of " + account.email().split("@")[0]);
                child.setPasswordHash(hash);
                child.setLevel(1);
                child.setCreatedAt(Instant.now());
                child.setTrialStatus("NONE");
                child.setAccountType("CHILD");
                child.setParentId(user.getId());
                userRepo.save(child);
            }

            created++;
            log.info("[DevSeed] Created test account: {} plan={}", account.email(), account.plan());
        }

        if (created > 0) {
            log.info("[DevSeed] Created {} test accounts. Password: {}", created, DEV_PASSWORD);
        } else {
            log.debug("[DevSeed] All test accounts already exist — nothing to seed");
        }
    }
}
