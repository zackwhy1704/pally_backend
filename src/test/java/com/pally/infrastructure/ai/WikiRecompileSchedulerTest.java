package com.pally.infrastructure.ai;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.BrainStateService;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for WikiRecompileScheduler.
 * Uses short debounce (200ms) and max-wait (1000ms) so tests run fast.
 * CompileWikiUseCase is mocked — zero API calls.
 */
@ExtendWith(MockitoExtension.class)
class WikiRecompileSchedulerTest {

    private static final long DEBOUNCE_MS = 200L;
    private static final long MAX_WAIT_MS = 1000L;

    @Mock CompileWikiUseCase compileWikiUseCase;
    @Mock WikiRepository wikiRepository;
    @Mock BrainStateService brainStateService;
    @Mock AvatarRepository avatarRepository;

    private WikiRecompileScheduler scheduler;
    private ThreadPoolExecutor aiTaskExecutor;

    @BeforeEach
    void setUp() {
        aiTaskExecutor = new ThreadPoolExecutor(
                4, 8, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(50));

        scheduler = new WikiRecompileScheduler(
                aiTaskExecutor,
                compileWikiUseCase,
                wikiRepository,
                brainStateService,
                avatarRepository,
                new com.pally.domain.knowledge.usecase.CompileJobStore(),
                org.mockito.Mockito.mock(com.pally.domain.knowledge.usecase.DurableCompileStatusStore.class));

        // Inject short debounce/maxWait for speed
        ReflectionTestUtils.setField(scheduler, "debounceMs", DEBOUNCE_MS);
        ReflectionTestUtils.setField(scheduler, "maxWaitMs", MAX_WAIT_MS);
        ReflectionTestUtils.setField(scheduler, "firstUploadImmediate", true);

        // Default: avatar already has active wiki pages (debounce path)
        lenient().when(wikiRepository.countActiveByAvatarId(any())).thenReturn(1);
        // Default: compile succeeds
        lenient().when(compileWikiUseCase.execute(any()))
                .thenReturn(new CompileWikiUseCase.CompileResult(1, 0, List.of("Page 1")));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
        aiTaskExecutor.shutdownNow();
    }

    // ── Test 1: Burst coalescing ─────────────────────────────────────────────

    @Test
    void burstCoalescing_5requests_executedExactlyOnce() throws InterruptedException {
        String avatarId = "avatar-burst";

        // Fire 5 rapid requests
        for (int i = 0; i < 5; i++) {
            scheduler.requestRecompile(avatarId);
        }

        // Wait for the debounce to fire and compile to finish
        Awaitility.await()
                .atMost(DEBOUNCE_MS * 3 + 500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, times(1)).execute(avatarId));
    }

    // ── Test 2: Max-wait ceiling ─────────────────────────────────────────────

    @Test
    void maxWait_repeatedCallsSpanningMaxWait_compilesFiredByMaxWait() throws InterruptedException {
        String avatarId = "avatar-maxwait";

        // Send requests every DEBOUNCE_MS/2 ms so debounce keeps resetting,
        // but eventually the max-wait ceiling should fire the compile.
        long end = System.currentTimeMillis() + MAX_WAIT_MS;
        while (System.currentTimeMillis() < end) {
            scheduler.requestRecompile(avatarId);
            Thread.sleep(DEBOUNCE_MS / 2);
        }

        // Compile should have fired at least once due to max-wait
        Awaitility.await()
                .atMost(MAX_WAIT_MS + DEBOUNCE_MS * 2, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, atLeastOnce()).execute(avatarId));
    }

    // ── Test 3: In-flight + dirtyAgain ──────────────────────────────────────

    @Test
    void inFlight_requestDuringCompile_triggersExactlyOneFollowUp() throws InterruptedException {
        String avatarId = "avatar-inflight";

        CountDownLatch compileLatch = new CountDownLatch(1);
        CountDownLatch compileStarted = new CountDownLatch(1);

        // Block the first compile until we signal
        AtomicInteger compileCount = new AtomicInteger(0);
        when(compileWikiUseCase.execute(avatarId)).thenAnswer(inv -> {
            compileStarted.countDown();
            compileLatch.await();
            compileCount.incrementAndGet();
            return new CompileWikiUseCase.CompileResult(1, 0, List.of("P1"));
        });

        // Trigger first compile via first-upload path
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(0);
        scheduler.requestRecompile(avatarId);

        // Wait for compile to start
        assertThat(compileStarted.await(2, TimeUnit.SECONDS)).isTrue();

        // Now request another compile while one is in-flight
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(1);
        scheduler.requestRecompile(avatarId);

        // Release the first compile
        compileLatch.countDown();

        // Wait for both compiles to complete
        Awaitility.await()
                .atMost(DEBOUNCE_MS * 3 + 2000, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, times(2)).execute(avatarId));

        // Should be exactly 2 — no more
        Thread.sleep(DEBOUNCE_MS * 2);
        verify(compileWikiUseCase, times(2)).execute(avatarId);
    }

    // ── Test 4: First-upload-immediate ──────────────────────────────────────

    @Test
    void firstUploadImmediate_noActivePages_compilesImmediately() {
        String avatarId = "avatar-firstupload";
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(0);

        scheduler.requestRecompile(avatarId);

        // Should fire immediately without waiting for debounce
        Awaitility.await()
                .atMost(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, times(1)).execute(avatarId));
    }

    @Test
    void firstUploadImmediate_withExistingPages_debounces() throws InterruptedException {
        String avatarId = "avatar-debounce";
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(5);

        scheduler.requestRecompile(avatarId);

        // Should NOT fire within 50ms (debounce is 200ms)
        Thread.sleep(50);
        verify(compileWikiUseCase, never()).execute(avatarId);

        // Should fire after debounce window
        Awaitility.await()
                .atMost(DEBOUNCE_MS + 500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, times(1)).execute(avatarId));
    }

    // ── Test 5: Delete-to-empty ──────────────────────────────────────────────

    @Test
    void deleteToEmpty_zeroFilesAfterDelete_compileStillTriggered() {
        String avatarId = "avatar-delete-empty";
        // Even if there are 0 active pages, requestRecompile still works
        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(0);

        scheduler.requestRecompile(avatarId);

        Awaitility.await()
                .atMost(DEBOUNCE_MS + 500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, atLeastOnce()).execute(avatarId));
    }

    // ── Test 6: recompileNow ─────────────────────────────────────────────────

    @Test
    void recompileNow_firesImmediatelyBypassingDebounce() {
        String avatarId = "avatar-recompile-now";

        scheduler.recompileNow(avatarId);

        Awaitility.await()
                .atMost(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, times(1)).execute(avatarId));
    }

    @Test
    void recompileNow_whileInFlight_queuesOneFollowUp() throws InterruptedException {
        String avatarId = "avatar-now-inflight";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        when(compileWikiUseCase.execute(avatarId)).thenAnswer(inv -> {
            count.incrementAndGet();
            latch.await();
            return new CompileWikiUseCase.CompileResult(1, 0, List.of());
        });

        when(wikiRepository.countActiveByAvatarId(avatarId)).thenReturn(0);
        scheduler.requestRecompile(avatarId); // starts first compile immediately

        // Wait for first compile to be in-flight
        Thread.sleep(100);

        // recompileNow while in-flight
        scheduler.recompileNow(avatarId);

        // release
        latch.countDown();

        // follow-up should fire
        Awaitility.await()
                .atMost(DEBOUNCE_MS * 3 + 1000, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(count.get()).isGreaterThanOrEqualTo(2));
    }

    // ── Test 7: Startup reconciler ───────────────────────────────────────────

    @Test
    void reconcileOnStartup_staleAvatarAndStuckAvatar_enqueuesAndResets() {
        // One stale avatar (needs recompile) and one COMPILING-stuck avatar
        String staleId  = "avatar-stale";
        String stuckId  = "avatar-stuck";

        when(wikiRepository.findAvatarIdsNeedingRecompile()).thenReturn(List.of(staleId));
        when(avatarRepository.findIdsByBrainState(Avatar.BrainState.COMPILING.name()))
                .thenReturn(List.of(stuckId));

        // stale avatar already has active pages (debounce path)
        when(wikiRepository.countActiveByAvatarId(staleId)).thenReturn(2);

        scheduler.reconcileOnStartup();

        // Stuck avatar should have been reset to READY
        verify(brainStateService, atLeastOnce()).markReady(stuckId);

        // Stale avatar should have had recompile requested
        Awaitility.await()
                .atMost(DEBOUNCE_MS * 3 + 2000, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, atLeastOnce()).execute(staleId));
    }

    // ── Test 8: Per-avatar isolation ─────────────────────────────────────────

    @Test
    void perAvatarIsolation_burstOnA_neverTriggersB() throws InterruptedException {
        String avatarA = "avatar-A";
        String avatarB = "avatar-B";

        // Fire 5 bursts on A
        for (int i = 0; i < 5; i++) {
            scheduler.requestRecompile(avatarA);
        }

        // Wait for A's compile
        Awaitility.await()
                .atMost(DEBOUNCE_MS * 3 + 500, TimeUnit.MILLISECONDS)
                .untilAsserted(() ->
                        verify(compileWikiUseCase, times(1)).execute(avatarA));

        // B must never have been compiled
        verify(compileWikiUseCase, never()).execute(avatarB);
    }

    // ── Auto-resume-on-startup ───────────────────────────────────────────────
    // A crash/redeploy that killed an in-flight compile leaves the avatar in COMPILING.
    // Startup must RESUME it (idempotent — executeBatched/module-gen skip done work), not
    // markReady/give-up. The old give-up stranded partial content: pages exist, so
    // findAvatarIdsNeedingRecompile misses it, and the uncompiled files never compile.

    @Test
    void reconcileOnStartup_resumesAvatarLeftMidCompile_ratherThanGivingUp() {
        String stuckId = "avatar-stuck-mid-compile";
        when(avatarRepository.findIdsByBrainState(Avatar.BrainState.COMPILING.name()))
                .thenReturn(List.of(stuckId));
        // No timestamp-stale avatars — isolate the stuck-resume path.
        when(wikiRepository.findAvatarIdsNeedingRecompile()).thenReturn(List.of());
        // 0 active pages → first-upload-immediate path resumes on the executor synchronously-ish.
        when(wikiRepository.countActiveByAvatarId(stuckId)).thenReturn(0);

        scheduler.reconcileOnStartup();

        // The compile is RE-RUN (resumed). The OLD code only markReady'd the stuck avatar and
        // NEVER re-ran the compile — so this verify FAILS against the give-up behaviour.
        Awaitility.await()
                .atMost(2, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(compileWikiUseCase, times(1)).execute(stuckId));
    }
}
