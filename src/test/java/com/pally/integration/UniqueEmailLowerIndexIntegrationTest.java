package com.pally.integration;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.shared.util.IdGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the V121 UNIQUE lower(email) index against the real schema + Flyway migrations.
 *
 * <p>The raw-column UNIQUE (V3) is case-SENSITIVE, so two rows differing ONLY in case
 * ({@code Case@x} vs {@code case@x}) are distinct raw values and slip past it — only the
 * functional {@code UNIQUE (lower(email))} from V121 catches them. This test therefore
 * FAILS on the old V114 non-unique index (both inserts succeed) and PASSES with V121.
 *
 * <p>It deliberately writes at the PERSISTENCE layer, bypassing EmailNormalizer, because
 * the point is the DB-level guarantee for any path that reaches persistence without
 * normalizing — the app-layer 409 for the register endpoint is a separate concern already
 * covered by the auth suite.
 */
class UniqueEmailLowerIndexIntegrationTest extends IntegrationTestBase {

    @Autowired
    private UserJpaRepository userRepo;

    private UserJpaEntity user(String email) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId(IdGenerator.newId());
        u.setEmail(email);
        u.setDisplayName("Dup Test");
        u.setCreatedAt(Instant.now());
        return u;
    }

    @Test
    void twoEmailsDifferingOnlyInCase_secondViolatesUniqueLowerIndex() {
        userRepo.saveAndFlush(user("CaseVariant@test.com"));

        // Same canonical email, different raw case — passes the raw-column UNIQUE (V3)
        // but must be rejected by UNIQUE (lower(email)) (V121).
        assertThatThrownBy(() -> userRepo.saveAndFlush(user("casevariant@test.com")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void distinctEmails_bothPersist() {
        // Guard the other direction: the unique index must not reject genuinely
        // different addresses.
        userRepo.saveAndFlush(user("alice-uniqidx@test.com"));
        userRepo.saveAndFlush(user("bob-uniqidx@test.com"));

        assertThat(userRepo.findByEmail("alice-uniqidx@test.com")).isPresent();
        assertThat(userRepo.findByEmail("bob-uniqidx@test.com")).isPresent();
    }
}
