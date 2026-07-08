package com.pally.infrastructure.ai;

import com.pally.domain.avatar.AvatarRepository;
import com.pally.domain.chat.port.ChatSessionCachePort;
import com.pally.domain.knowledge.WikiPage;
import com.pally.domain.knowledge.WikiRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fires a minimal keepalive ping every 4 minutes to reset the 5-minute cache TTL.
 *
 * <p>Without keepalive, if there's a gap in conversation exceeding 5 minutes,
 * Block 3 (wiki pages) cache expires and the next message pays a cache write cost.
 * The ping uses Haiku with max_tokens=1 — negligible cost ($0.00001 per ping).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CacheKeepAliveService implements ChatSessionCachePort {

    private static final int KEEPALIVE_INTERVAL_MINUTES = 4;

    private final ClaudeApiClient claudeClient;
    private final ClaudeContextAssembler assembler;
    private final AvatarRepository avatarRepo;
    private final WikiRepository wikiRepo;
    private final ModelRouter modelRouter;
    private final com.pally.domain.cost.AiUsageMeter aiUsageMeter;

    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(4, Thread.ofVirtual().factory());

    /**
     * Start keepalive when a user opens the chat screen.
     * Cancels any existing task for the same avatar first.
     */
    public void startKeepalive(String avatarId) {
        stopKeepalive(avatarId);

        // Pre-warm cache immediately (non-blocking) so first user message has warm cache.
        // Anthropic reports 50-85% TTFT reduction when cache is hit on first turn.
        scheduler.submit(() -> {
            try {
                pingCache(avatarId);
                log.info("[CachePrewarm] Warmed cache for avatar={}", avatarId);
            } catch (Exception e) {
                log.warn("[CachePrewarm] Failed for avatar={}: {}", avatarId, e.getMessage());
            }
        });

        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(
                () -> pingCache(avatarId),
                KEEPALIVE_INTERVAL_MINUTES, KEEPALIVE_INTERVAL_MINUTES, TimeUnit.MINUTES);

        activeTasks.put(avatarId, task);
        log.debug("[CacheKeepalive] Started for avatar={}", avatarId);
    }

    /**
     * Stop keepalive when user closes chat or navigates away.
     */
    public void stopKeepalive(String avatarId) {
        ScheduledFuture<?> existing = activeTasks.remove(avatarId);
        if (existing != null) {
            existing.cancel(false);
            log.debug("[CacheKeepalive] Stopped for avatar={}", avatarId);
        }
    }

    public boolean isActive(String avatarId) {
        return activeTasks.containsKey(avatarId);
    }

    // Haiku cache threshold — must match ClaudeContextAssembler.CACHE_MIN_TOKENS
    private static final int CACHE_MIN_TOKENS = 2048;

    private void pingCache(String avatarId) {
        try {
            var avatar = avatarRepo.findById(avatarId).orElse(null);
            if (avatar == null) {
                stopKeepalive(avatarId);
                return;
            }

            List<WikiPage> allPages = wikiRepo.findByAvatarId(avatarId);

            // Skip keepalive when the system prompt is too small to cache.
            // Haiku requires ≥ 2048 tokens in the cached blocks for cache_control
            // to write. Sending a ping for a small avatar just bills us for the
            // input tokens without any caching benefit — a pure waste.
            int estimatedTokens = allPages.stream()
                    .mapToInt(p -> p.getContent() != null ? p.getContent().length() / 4 : 0)
                    .sum() + 500; // +500 for Block1+Block2 overhead estimate
            if (estimatedTokens < CACHE_MIN_TOKENS) {
                log.debug("[CacheKeepalive] Skipping avatar={} (~{}t < {} threshold — nothing to cache)",
                        avatarId, estimatedTokens, CACHE_MIN_TOKENS);
                return;
            }

            List<Map<String, Object>> systemBlocks = assembler.assembleSystemBlocks(avatar, allPages);

            // Check if any block has cache_control — if assembler decided not to
            // add it (because threshold not met), keepalive is pointless.
            boolean hasCacheControl = systemBlocks.stream()
                    .anyMatch(b -> b.containsKey("cache_control"));
            if (!hasCacheControl) {
                log.debug("[CacheKeepalive] Skipping avatar={} — no cache_control blocks assembled", avatarId);
                return;
            }

            List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "ping"));
            claudeClient.streamResponseWithCacheAndModel(modelRouter.forCacheKeepalive(), 1, systemBlocks, messages)
                    .blockLast(Duration.ofSeconds(10));

            // Cost ledger: this scheduled ping was unmetered. Its whole point is to
            // hit the WARM cache, so bill the corpus as a cache-READ (0.1x effective)
            // + 1 output token. Estimated (we discard the stream's usage), flagged.
            aiUsageMeter.record(avatar.getUserId(), avatarId,
                    com.pally.domain.cost.AiCallType.OTHER, "cache-keepalive",
                    com.pally.domain.cost.AiTrigger.SCHEDULED, modelRouter.forCacheKeepalive(),
                    Math.round(estimatedTokens * 0.10), 1, true, true);

            log.debug("[CacheKeepalive] Ping sent for avatar={} (~{}t)", avatarId, estimatedTokens);

        } catch (Exception e) {
            log.warn("[CacheKeepalive] Ping failed for avatar={}: {}", avatarId, e.getMessage());
        }
    }
}
