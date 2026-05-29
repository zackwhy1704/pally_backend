package com.pally.domain.shop;

import com.pally.infrastructure.persistence.progress.UserJpaEntity;
import com.pally.infrastructure.persistence.progress.UserJpaRepository;
import com.pally.infrastructure.persistence.shop.CharacterUnlockJpaRepository;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// D1 fix verification with a real concurrency harness. We simulate the
/// DB's atomic UPDATE behaviour with an AtomicInteger so the unit test
/// can prove that 20 parallel buy attempts on a 300-star balance result
/// in EXACTLY 2 successes (300 / 150 = 2) and 18 failures, with the
/// final balance at 0. Without the atomic guard the test would routinely
/// see 3+ "successes" because two threads can both read 150 → write 0.
class BuyStreakFreezeConcurrencyTest {

    @Test
    void parallelBuys_canNeverExceedBudget() throws Exception {
        final int threads = 20;
        final int startingStars = 300;   // budget for exactly 2 freezes
        final int cap = 5;               // plenty of headroom
        final AtomicInteger stars = new AtomicInteger(startingStars);
        final AtomicInteger freezes = new AtomicInteger(0);

        UserJpaRepository userRepo = mock(UserJpaRepository.class);
        CharacterUnlockJpaRepository unlockRepo =
                mock(CharacterUnlockJpaRepository.class);
        var catalogRepo = mock(
                com.pally.infrastructure.persistence.mochi.MochiCharacterJpaRepository.class);
        var userMochiRepo = mock(
                com.pally.infrastructure.persistence.mochi.UserMochiJpaRepository.class);
        var shop = new CharacterShopService(unlockRepo, userRepo,
                catalogRepo, userMochiRepo);

        // Pre-flight read returns the current state.
        lenient().when(userRepo.findById(anyString())).thenAnswer(
                inv -> Optional.of(snapshot(stars.get(), freezes.get())));
        // Atomic UPDATE: only succeeds when there are enough stars AND
        // the cap allows it. The synchronized block + compareAndSet pair
        // simulates Postgres's row-level guarantee.
        when(userRepo.buyStreakFreeze(eq("u1"), eq(150), eq(cap)))
                .thenAnswer(inv -> {
                    while (true) {
                        int cur = stars.get();
                        if (cur < 150) return 0;
                        int curFreezes = freezes.get();
                        if (curFreezes >= cap) return 0;
                        if (stars.compareAndSet(cur, cur - 150)) {
                            freezes.incrementAndGet();
                            return 1;
                        }
                        // CAS lost — retry.
                    }
                });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap.KeySetView<String, Boolean> successes =
                ConcurrentHashMap.newKeySet();
        ConcurrentHashMap.KeySetView<String, Boolean> failures =
                ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    shop.buyStreakFreeze("u1");
                    successes.add("t" + idx);
                } catch (BusinessException expected) {
                    failures.add("t" + idx);
                } catch (Exception other) {
                    // Anything else is a real bug.
                    failures.add("ERR-" + other.getClass().getSimpleName());
                }
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // 300 stars / 150 per buy = at most 2 successes. Anything more
        // means we lost an update — the very bug D1 was filed to close.
        assertThat(successes).hasSize(2);
        assertThat(failures).hasSize(18);
        assertThat(stars.get()).isEqualTo(0);
        assertThat(freezes.get()).isEqualTo(2);
    }

    private UserJpaEntity snapshot(int stars, int freezes) {
        UserJpaEntity u = new UserJpaEntity();
        u.setId("u1");
        u.setStars(stars);
        u.setStreakFreezes(freezes);
        u.setLevel(20); // engages the L20 cap = 5
        return u;
    }
}
