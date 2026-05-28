package com.pally.infrastructure.ai;

import com.pally.domain.avatar.AvatarRepository;
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
public class CacheKeepAliveService {

    private static final int KEEPALIVE_INTERVAL_MINUTES = 4;

    private final ClaudeApiClient claudeClient;
    private final ClaudeContextAssembler assembler;
    private final AvatarRepository avatarRepo;
    private final WikiRepository wikiRepo;
    private final ModelRouter modelRouter;

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

    private void pingCache(String avatarId) {
        try {
            var avatar = avatarRepo.findById(avatarId).orElse(null);
            if (avatar == null) {
                stopKeepalive(avatarId);
                return;
            }

            List<WikiPage> allPages = wikiRepo.findByAvatarId(avatarId);
            List<Map<String, Object>> systemBlocks = assembler.assembleSystemBlocks(avatar, allPages);

            List<Map<String, String>> messages = List.of(Map.of("role", "user", "content", "ping"));

            claudeClient.streamResponseWithCacheAndModel(modelRouter.forCacheKeepalive(), 1, systemBlocks, messages)
                    .blockLast(Duration.ofSeconds(10));

            log.debug("[CacheKeepalive] Ping sent for avatar={}", avatarId);

        } catch (Exception e) {
            log.warn("[CacheKeepalive] Ping failed for avatar={}: {}", avatarId, e.getMessage());
        }
    }
}
