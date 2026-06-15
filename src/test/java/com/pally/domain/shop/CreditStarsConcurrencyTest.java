package com.pally.domain.shop;

import com.pally.domain.user.User;
import com.pally.domain.user.UserRepository;
import com.pally.infrastructure.persistence.mochi.MochiCharacterJpaRepository;
import com.pally.infrastructure.persistence.mochi.UserMochiJpaRepository;
import com.pally.infrastructure.persistence.powerup.UserPowerupJpaRepository;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreditStarsConcurrencyTest {

    @Test
    void parallelCredits_neverDropped() throws Exception {
        final int threads = 20;
        final int creditAmount = 50;
        final AtomicInteger stars = new AtomicInteger(0);

        UserRepository userRepo = mock(UserRepository.class);
        var service = new CharacterShopService(
                mock(CharacterUnlockJpaRepository.class), userRepo,
                mock(MochiCharacterJpaRepository.class), mock(UserMochiJpaRepository.class));

        when(userRepo.addXpAndStars(anyString(), eq(0), eq(creditAmount))).thenAnswer(inv -> {
            stars.addAndGet(creditAmount);
            return UserRepository.XpResult.unchanged(0, 1);
        });
        lenient().when(userRepo.findById(anyString())).thenAnswer(inv -> {
            User u = new User(); u.setId("u1"); u.setStars(stars.get()); return Optional.of(u);
        });

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> { try { start.await(); service.creditStars("u1", creditAmount); } catch (Exception ignored) {} return null; });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(stars.get()).isEqualTo(threads * creditAmount);
    }

    @Test
    void creditAndConcurrentSpend_netsCorrectly() throws Exception {
        // 10 credits of 100 stars and 5 spends of 100 stars run concurrently
        // against a shared AtomicInteger. All credits succeed; spends succeed
        // only when the balance allows. Final balance = credits*100 - spent*100.
        final int creditThreads = 10;
        final int spendThreads = 5;
        final int amount = 100;
        final AtomicInteger stars = new AtomicInteger(0);

        UserRepository userRepo = mock(UserRepository.class);
        UserPowerupJpaRepository powerupRepo = mock(UserPowerupJpaRepository.class);

        var shopService = new CharacterShopService(
                mock(CharacterUnlockJpaRepository.class), userRepo,
                mock(MochiCharacterJpaRepository.class), mock(UserMochiJpaRepository.class));
        var powerupService = new PowerupService(userRepo, powerupRepo);

        when(userRepo.addXpAndStars(anyString(), eq(0), eq(amount))).thenAnswer(inv -> {
            stars.addAndGet(amount);
            return UserRepository.XpResult.unchanged(0, 1);
        });

        // The spend cost must match HINT_TOKEN.starCost (50). Use spendStars(userId, 50).
        final int spendCost = PowerupService.Type.HINT_TOKEN.starCost;
        when(userRepo.spendStars(anyString(), eq(spendCost))).thenAnswer(inv -> {
            while (true) {
                int cur = stars.get();
                if (cur < spendCost) return 0;
                if (stars.compareAndSet(cur, cur - spendCost)) return 1;
            }
        });

        lenient().when(userRepo.findById(anyString())).thenAnswer(inv -> {
            User u = new User(); u.setId("u1"); u.setStars(stars.get()); return Optional.of(u);
        });
        lenient().when(powerupRepo.upsertCount(anyString(), anyString(), anyInt())).thenReturn(1);
        lenient().when(powerupRepo.findById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(new com.pally.infrastructure.persistence.powerup.UserPowerupJpaEntity("u1", "HINT_TOKEN", 1)));

        ExecutorService pool = Executors.newFixedThreadPool(creditThreads + spendThreads);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentHashMap.KeySetView<String, Boolean> spendSuccesses = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < creditThreads; i++) {
            pool.submit(() -> { try { start.await(); shopService.creditStars("u1", amount); } catch (Exception ignored) {} return null; });
        }
        for (int i = 0; i < spendThreads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    powerupService.buy("u1", PowerupService.Type.HINT_TOKEN);
                    spendSuccesses.add("s" + idx);
                } catch (BusinessException ignored) {
                } catch (Exception ignored) {}
                return null;
            });
        }

        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        // Balance is always non-negative and equals exactly what atomics compute.
        assertThat(stars.get()).isGreaterThanOrEqualTo(0);
        int expectedNet = creditThreads * amount - spendSuccesses.size() * spendCost;
        assertThat(stars.get()).isEqualTo(expectedNet);
    }
}
