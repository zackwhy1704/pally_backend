package com.pally.domain.progress.usecase;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenMysteryBoxConcurrencyTest {

    @Test
    void parallelOpens_canNeverExceedBudget() throws Exception {
        final int threads = 10;
        final int budget = 5; // exactly 5 opens affordable
        final AtomicInteger stars = new AtomicInteger(budget * OpenMysteryBoxUseCase.BOX_COST);

        UserRepository userRepo = mock(UserRepository.class);
        var useCase = new OpenMysteryBoxUseCase(userRepo);

        lenient().doNothing().when(userRepo).ensureUserExists(anyString());

        when(userRepo.spendStars(eq("u1"), eq(OpenMysteryBoxUseCase.BOX_COST)))
                .thenAnswer(inv -> {
                    while (true) {
                        int cur = stars.get();
                        if (cur < OpenMysteryBoxUseCase.BOX_COST) return 0;
                        if (stars.compareAndSet(cur, cur - OpenMysteryBoxUseCase.BOX_COST)) return 1;
                    }
                });

        lenient().when(userRepo.findById(eq("u1"))).thenAnswer(inv -> {
            User u = new User();
            u.setId("u1");
            u.setStars(stars.get());
            return Optional.of(u);
        });

        lenient().when(userRepo.addXpAndStars(eq("u1"), eq(OpenMysteryBoxUseCase.BOX_XP_BONUS), eq(0)))
                .thenReturn(UserRepository.XpResult.unchanged(0, 1));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap.KeySetView<String, Boolean> successes = ConcurrentHashMap.newKeySet();
        ConcurrentHashMap.KeySetView<String, Boolean> failures = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    useCase.execute("u1");
                    successes.add("t" + idx);
                } catch (BusinessException expected) {
                    failures.add("t" + idx);
                } catch (Exception other) {
                    failures.add("ERR-" + other.getClass().getSimpleName() + "-" + idx);
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successes).hasSize(budget);
        assertThat(failures).hasSize(threads - budget);
        assertThat(stars.get()).isEqualTo(0);
    }
}
