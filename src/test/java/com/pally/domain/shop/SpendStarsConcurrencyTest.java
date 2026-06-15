package com.pally.domain.shop;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.powerup.UserPowerupJpaEntity;
import com.pally.infrastructure.persistence.powerup.UserPowerupJpaRepository;
import com.pally.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpendStarsConcurrencyTest {

    @Test
    void parallelPowerupBuys_canNeverExceedBudget() throws Exception {
        final int threads = 20;
        final int startingStars = 100;
        final AtomicInteger stars = new AtomicInteger(startingStars);

        UserRepository userRepo = mock(UserRepository.class);
        UserPowerupJpaRepository powerupRepo = mock(UserPowerupJpaRepository.class);
        var service = new PowerupService(userRepo, powerupRepo);

        lenient().when(userRepo.findById(anyString())).thenAnswer(inv -> {
            User u = new User();
            u.setId("u1");
            u.setStars(stars.get());
            return Optional.of(u);
        });

        when(userRepo.spendStars(eq("u1"), eq(50)))
                .thenAnswer(inv -> {
                    while (true) {
                        int cur = stars.get();
                        if (cur < 50) return 0;
                        if (stars.compareAndSet(cur, cur - 50)) return 1;
                    }
                });

        lenient().when(powerupRepo.findById(any()))
                .thenReturn(Optional.of(new UserPowerupJpaEntity("u1", "HINT_TOKEN", 1)));
        lenient().when(powerupRepo.upsertCount(anyString(), anyString(), anyInt()))
                .thenReturn(1);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap.KeySetView<String, Boolean> successes = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap.KeySetView<String, Boolean> failures = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    service.buy("u1", PowerupService.Type.HINT_TOKEN);
                    successes.add("t" + idx);
                } catch (BusinessException expected) {
                    failures.add("t" + idx);
                } catch (Exception other) {
                    failures.add("ERR-" + other.getClass().getSimpleName());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes).hasSize(2);
        assertThat(failures).hasSize(18);
        assertThat(stars.get()).isEqualTo(0);
    }
}
