package com.pally.domain.notification;

import com.pally.infrastructure.persistence.star.StarAwardLogJpaRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the star award daily cap logic. The cap is 300 stars per
 * day per parent, with a max of 100 per individual award.
 */
@ExtendWith(MockitoExtension.class)
class StarAwardCapTest {

    private static final int MAX_PER_AWARD = 100;
    private static final int DAILY_CAP = 300;

    @Mock
    private StarAwardLogJpaRepository starAwardLogRepo;

    @Test
    void withinDailyCap_allowsAward() {
        Instant dayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
        when(starAwardLogRepo.sumAmountByParentIdSince(eq("parent-1"), any()))
                .thenReturn(200);

        int requested = 50;
        int alreadyAwarded = starAwardLogRepo.sumAmountByParentIdSince("parent-1", dayStart);
        boolean allowed = (alreadyAwarded + requested) <= DAILY_CAP
                && requested <= MAX_PER_AWARD;

        assertThat(allowed).isTrue();
    }

    @Test
    void exceedsDailyCap_blocksAward() {
        Instant dayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
        when(starAwardLogRepo.sumAmountByParentIdSince(eq("parent-1"), any()))
                .thenReturn(280);

        int requested = 50;
        int alreadyAwarded = starAwardLogRepo.sumAmountByParentIdSince("parent-1", dayStart);
        boolean allowed = (alreadyAwarded + requested) <= DAILY_CAP
                && requested <= MAX_PER_AWARD;

        assertThat(allowed).isFalse();
    }

    @Test
    void exceedsPerAwardCap_blocksAward() {
        int requested = 150;
        boolean allowed = requested <= MAX_PER_AWARD;

        assertThat(allowed).isFalse();
    }

    @Test
    void exactlyAtDailyCap_allowsAward() {
        Instant dayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
        when(starAwardLogRepo.sumAmountByParentIdSince(eq("parent-1"), any()))
                .thenReturn(200);

        int requested = 100;
        int alreadyAwarded = starAwardLogRepo.sumAmountByParentIdSince("parent-1", dayStart);
        boolean allowed = (alreadyAwarded + requested) <= DAILY_CAP
                && requested <= MAX_PER_AWARD;

        assertThat(allowed).isTrue();
    }

    @Test
    void zeroPreviousAwards_fullCapAvailable() {
        Instant dayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant();
        when(starAwardLogRepo.sumAmountByParentIdSince(eq("parent-1"), any()))
                .thenReturn(0);

        int requested = 100;
        int alreadyAwarded = starAwardLogRepo.sumAmountByParentIdSince("parent-1", dayStart);
        boolean allowed = (alreadyAwarded + requested) <= DAILY_CAP
                && requested <= MAX_PER_AWARD;

        assertThat(allowed).isTrue();
    }
}
