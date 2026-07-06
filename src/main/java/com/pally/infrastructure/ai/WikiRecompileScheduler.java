package com.pally.infrastructure.ai;

import com.pally.domain.avatar.Avatar;
import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.knowledge.BrainStateService;
import com.pally.domain.knowledge.WikiRepository;
import com.pally.domain.knowledge.usecase.CompileWikiUseCase;
import com.pally.infrastructure.config.AiTaskExecutorConfig;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Debounced wiki recompile scheduler.
 *
 * <p>Burst-coalescing: multiple requestRecompile() calls within
 * {@code WIKI_DEBOUNCE_MS} ms produce exactly one compile. The max-wait
 * ceiling ({@code WIKI_MAXWAIT_MS}) ensures a compile fires even under
 * continuous upload traffic.
 *
 * <p>In-flight guard: only one compile runs per avatar at a time. If a
 * second call arrives while one is running, a dirty flag triggers exactly
 * one follow-up compile after the current one finishes.
 *
 * <p>Startup reconciler: on ApplicationReadyEvent, resets avatars stuck in
 * COMPILING (from a previous crash/redeploy) and enqueues recompiles for
 * avatars where knowledge files are newer than wiki pages.
 */
@Service
@Slf4j
public class WikiRecompileScheduler {

    @Value("${WIKI_DEBOUNCE_MS:8000}")
    private long debounceMs;

    @Value("${WIKI_MAXWAIT_MS:60000}")
    private long maxWaitMs;

    @Value("${WIKI_FIRST_IMMEDIATE:true}")
    private boolean firstUploadImmediate;

    private final ScheduledExecutorService timer =
            Executors.newScheduledThreadPool(2, Thread.ofVirtual().factory());

    private final ConcurrentHashMap<String, ScheduledFuture<?>> pending        = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>               firstQueuedAt  = new ConcurrentHashMap<>();
    private final Set<String>                                    inFlight       = ConcurrentHashMap.newKeySet();
    private final Set<String>                                    dirtyAgain     = ConcurrentHashMap.newKeySet();

    // Daily compile budget guard: counts compiles per avatar per UTC day.
    // Prevents a spam-uploading user from triggering unbounded Gemini calls.
    private static final int DAILY_COMPILE_WARN_THRESHOLD  = 20;
    private static final int DAILY_COMPILE_HARD_CAP        = 50;
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> dailyCompileCount =
            new ConcurrentHashMap<>();
    private volatile java.time.LocalDate dailyCountDate = java.time.LocalDate.now(com.pally.shared.util.PallyTime.SGT);

    private final ThreadPoolExecutor  aiTaskExecutor;
    private final CompileWikiUseCase  compileWikiUseCase;
    private final WikiRepository      wikiRepository;
    private final BrainStateService   brainStateService;
    private final AvatarRepository    avatarRepository;
    private final com.pally.domain.knowledge.usecase.CompileJobStore compileJobStore;
    private final com.pally.domain.knowledge.usecase.DurableCompileStatusStore durableCompileStatusStore;

    public WikiRecompileScheduler(
            @Qualifier(AiTaskExecutorConfig.AI_TASK_EXECUTOR) ThreadPoolExecutor aiTaskExecutor,
            CompileWikiUseCase compileWikiUseCase,
            WikiRepository wikiRepository,
            BrainStateService brainStateService,
            AvatarRepository avatarRepository,
            com.pally.domain.knowledge.usecase.CompileJobStore compileJobStore,
            com.pally.domain.knowledge.usecase.DurableCompileStatusStore durableCompileStatusStore) {
        this.aiTaskExecutor    = aiTaskExecutor;
        this.compileWikiUseCase = compileWikiUseCase;
        this.wikiRepository    = wikiRepository;
        this.brainStateService = brainStateService;
        this.avatarRepository  = avatarRepository;
        this.compileJobStore   = compileJobStore;
        this.durableCompileStatusStore = durableCompileStatusStore;
    }

    /**
     * Request a recompile for the given avatar. For the very first upload
     * (no active wiki pages yet) the compile fires immediately so the user
     * sees brain pages as fast as possible. All subsequent uploads are
     * debounced to coalesce burst uploads into a single compile.
     */
    public void requestRecompile(String avatarId) {
        // First-upload-immediate: no existing pages AND not already compiling
        if (firstUploadImmediate
                && wikiRepository.countActiveByAvatarId(avatarId) == 0
                && inFlight.add(avatarId)) {
            log.info("[Debounce] First upload for avatar={} — compiling immediately", avatarId);
            safeMarkCompiling(avatarId);
            aiTaskExecutor.execute(() -> runCompile(avatarId));
            return;
        }

        // Debounce path
        firstQueuedAt.putIfAbsent(avatarId, System.currentTimeMillis());

        ScheduledFuture<?> old = pending.remove(avatarId);
        if (old != null) old.cancel(false);

        long elapsed = System.currentTimeMillis()
                - firstQueuedAt.getOrDefault(avatarId, System.currentTimeMillis());
        long delay = Math.min(debounceMs, Math.max(0L, maxWaitMs - elapsed));

        ScheduledFuture<?> future = timer.schedule(() -> fire(avatarId), delay, TimeUnit.MILLISECONDS);
        pending.put(avatarId, future);
        safeMarkPending(avatarId);
        log.debug("[Debounce] Scheduled recompile avatar={} in {}ms (elapsed={}ms)",
                avatarId, delay, elapsed);
    }

    // ── External-compile coordination (A1) ──────────────────────────────────────
    // The explicit POST /wiki/compile path runs OUTSIDE this scheduler but must share
    // its per-avatar in-flight gate, or an explicit compile and a debounced recompile
    // run for the same avatar at once → duplicate-key races on wiki_pages /
    // learning_module. The explicit path calls tryBeginExternalCompile before
    // compiling and endExternalCompile in a finally; the scheduler's own
    // requestRecompile/recompileNow already gate on the same {@code inFlight} set, so
    // the two can never overlap for one avatar.

    /** Try to claim the per-avatar gate for an external (explicit) compile. */
    public boolean tryBeginExternalCompile(String avatarId) {
        return inFlight.add(avatarId);
    }

    /** Release the gate after an external compile; fire a queued follow-up if content
     *  changed while it ran (mirrors the scheduler's own dirtyAgain coalescing). */
    public void endExternalCompile(String avatarId) {
        inFlight.remove(avatarId);
        if (dirtyAgain.remove(avatarId)) {
            log.info("[Debounce] Follow-up recompile after external compile avatar={}", avatarId);
            requestRecompile(avatarId);
        }
    }

    /** Mark the avatar dirty so a recompile runs once the in-flight compile finishes. */
    public void markDirty(String avatarId) {
        dirtyAgain.add(avatarId);
    }

    /**
     * Bypass debounce and fire immediately. Used by the manual
     * {@code POST /wiki/recompile} endpoint.
     */
    public void recompileNow(String avatarId) {
        ScheduledFuture<?> f = pending.remove(avatarId);
        if (f != null) f.cancel(false);
        firstQueuedAt.remove(avatarId);

        if (!inFlight.add(avatarId)) {
            // Already compiling — mark dirty so a follow-up fires
            dirtyAgain.add(avatarId);
            log.info("[Debounce] recompileNow avatar={} already in-flight — follow-up queued", avatarId);
            return;
        }
        log.info("[Debounce] recompileNow avatar={} — firing immediately", avatarId);
        safeMarkCompiling(avatarId);
        aiTaskExecutor.execute(() -> runCompile(avatarId));
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private void fire(String avatarId) {
        pending.remove(avatarId);
        firstQueuedAt.remove(avatarId);

        if (!inFlight.add(avatarId)) {
            dirtyAgain.add(avatarId);
            log.debug("[Debounce] fire() avatar={} already in-flight — follow-up queued", avatarId);
            return;
        }
        log.info("[Debounce] Firing recompile for avatar={}", avatarId);
        safeMarkCompiling(avatarId);
        aiTaskExecutor.execute(() -> runCompile(avatarId));
    }

    private void runCompile(String avatarId) {
        // Daily compile budget guard — resets at UTC midnight automatically
        java.time.LocalDate today = java.time.LocalDate.now(com.pally.shared.util.PallyTime.SGT);
        if (!today.equals(dailyCountDate)) {
            dailyCountDate = today;
            dailyCompileCount.clear();
        }
        int todayCount = dailyCompileCount
                .computeIfAbsent(avatarId, k -> new java.util.concurrent.atomic.AtomicInteger(0))
                .incrementAndGet();
        if (todayCount > DAILY_COMPILE_HARD_CAP) {
            log.error("[Debounce] HARD CAP hit: avatar={} has {} compiles today (limit {}). " +
                      "Skipping to prevent runaway Gemini spend.", avatarId, todayCount, DAILY_COMPILE_HARD_CAP);
            inFlight.remove(avatarId);
            safeMarkReady(avatarId);
            return;
        }
        if (todayCount >= DAILY_COMPILE_WARN_THRESHOLD) {
            log.warn("[Debounce] HIGH compile rate: avatar={} has {} compiles today. " +
                     "Check for upload spam (hard cap at {}).", avatarId, todayCount, DAILY_COMPILE_HARD_CAP);
        }

        boolean failed = false;
        try {
            // Capture the result so per-page failures aren't lost: the centre web
            // polls /avatars then reads GET /wiki/compile/status, which serves this
            // store via findByAvatarId. Without this, a partial recompile (some pages
            // failed to persist) looks like a full success to the teacher.
            CompileWikiUseCase.CompileResult result = compileWikiUseCase.execute(avatarId);
            recordRecompileStatus(avatarId, result);
        } catch (Exception e) {
            failed = true;
            boolean isTimeout = e.getMessage() == null
                    || e.getMessage().toLowerCase().contains("timeout")
                    || e.getCause() instanceof io.netty.handler.timeout.ReadTimeoutException
                    || (e.getCause() != null && e.getCause().getMessage() != null
                        && e.getCause().getMessage().toLowerCase().contains("timeout"));
            if (isTimeout) {
                log.warn("[Debounce] recompile timed out for avatar={} — scheduling retry in 2min", avatarId);
            } else {
                log.error("[Debounce] recompile failed avatar={}", avatarId, e);
            }
        } finally {
            inFlight.remove(avatarId);
            safeMarkReady(avatarId);
            if (dirtyAgain.remove(avatarId)) {
                log.info("[Debounce] Follow-up recompile queued for avatar={}", avatarId);
                requestRecompile(avatarId);
            } else if (failed) {
                // Schedule a single retry after 2 minutes so a transient Anthropic
                // timeout or slow response doesn't permanently leave the brain empty.
                // Uses timer (not aiTaskExecutor) to avoid blocking the pool.
                timer.schedule(() -> requestRecompile(avatarId), 2, java.util.concurrent.TimeUnit.MINUTES);
                log.info("[Debounce] Retry scheduled in 2min for avatar={}", avatarId);
            }
        }
    }

    /// Publish the recompile's per-page outcome to the CompileJobStore (keyed by
    /// avatar via a stable jobId) so GET /wiki/compile/status surfaces failedPages.
    /// Always writes a fresh DONE entry — including 0 failures — so the latest
    /// recompile's result wins findByAvatarId and a prior partial doesn't linger.
    void recordRecompileStatus(String avatarId, CompileWikiUseCase.CompileResult result) {
        int compiled = result.pagesCreated() + result.pagesUpdated();
        int total = compiled + result.failedPages().size();
        var status = new com.pally.domain.knowledge.usecase.CompileJobStore.JobStatus(
                "recompile-" + avatarId, avatarId,
                com.pally.domain.knowledge.usecase.CompileJobStore.JobState.DONE,
                compiled, total, result.tierServed(), null,
                result.failedPages(), java.time.Instant.now());
        compileJobStore.put("recompile-" + avatarId, status);
        // C2: also persist durably so GET /wiki/compile/status is correct on any replica.
        try {
            durableCompileStatusStore.record(avatarId, "DONE", compiled, total, result.failedPages());
        } catch (Exception e) {
            log.warn("[Debounce] durable compile-status write failed avatar={}: {}", avatarId, e.getMessage());
        }
        if (!result.failedPages().isEmpty()) {
            log.warn("[Debounce] recompile avatar={} persisted {}/{} pages — {} failed: {}",
                    avatarId, compiled, total, result.failedPages().size(), result.failedPages());
        }
    }

    // ── Startup reconciler ──────────────────────────────────────────────────

    /**
     * On startup: reset avatars stuck in COMPILING state (from a crash/redeploy)
     * and enqueue recompiles for avatars where files are newer than wiki pages.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        // RESUME avatars left mid-compile by a crash/redeploy (brainState=COMPILING when the
        // instance died). The compile is idempotent — executeBatched skips already-compiled
        // files (compiled_by set) and ModuleGenerationService skips built modules — so
        // re-triggering RESUMES from where it stopped instead of giving up (the old markReady,
        // which stranded partial content: pages exist so findAvatarIdsNeedingRecompile below
        // misses it, and the uncompiled files never get compiled). This makes a redeploy
        // mid-compile a non-event rather than lost work needing a manual retry.
        List<String> stuck = avatarRepository.findIdsByBrainState(
                Avatar.BrainState.COMPILING.name());
        if (!stuck.isEmpty()) {
            log.info("[Debounce] Startup: resuming {} avatars left mid-compile", stuck.size());
            stuck.forEach(id -> {
                try {
                    requestRecompile(id); // resume (idempotent), not markReady (give up)
                } catch (Exception e) {
                    // Queue full / transient — don't leave it wedged in COMPILING forever.
                    log.warn("[Debounce] Could not resume stuck avatar={}: {} — marking ready",
                            id, e.getMessage());
                    try {
                        brainStateService.markReady(id);
                    } catch (Exception ignored) {
                        // best-effort
                    }
                }
            });
        }

        // Enqueue recompile for avatars where files are newer than wiki pages
        List<String> stale = wikiRepository.findAvatarIdsNeedingRecompile();
        log.info("[Debounce] Startup reconciler: {} avatars need recompile", stale.size());
        for (String id : stale) {
            try {
                Thread.sleep(500); // stagger to avoid bursting on startup
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
            requestRecompile(id);
        }
    }

    // ── Safe state helpers (never throw) ────────────────────────────────────

    private void safeMarkPending(String avatarId) {
        try {
            brainStateService.markPending(avatarId);
        } catch (Exception e) {
            log.warn("[Brain] markPending failed avatar={}: {}", avatarId, e.getMessage());
        }
    }

    private void safeMarkCompiling(String avatarId) {
        try {
            brainStateService.markCompiling(avatarId);
        } catch (Exception e) {
            log.warn("[Brain] markCompiling failed avatar={}: {}", avatarId, e.getMessage());
        }
    }

    private void safeMarkReady(String avatarId) {
        try {
            brainStateService.markReady(avatarId);
        } catch (Exception e) {
            log.warn("[Brain] markReady failed avatar={}: {}", avatarId, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        timer.shutdownNow();
    }
}
