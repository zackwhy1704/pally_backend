package com.pally.domain.module;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-avatar live progress for the module-generation phase of a compile
 * (one page's wiki extraction is done, but LEARN/TEST modules still need generating
 * for each page — the phase the avatar-status poll was previously blind to; see
 * {@link com.pally.api.avatar.AvatarMapper}). Mirrors {@link CompileJobStore}'s
 * tradeoff exactly: no DB table, cleared on restart/replica change — fine for a
 * polling client that just needs "is a module completing recently", not a durable
 * audit trail. Entries auto-expire after 30 minutes.
 */
@Component
public class ModuleGenerationProgressStore {

    private static final Logger log = LoggerFactory.getLogger(ModuleGenerationProgressStore.class);
    private static final long EXPIRY_MS = 30 * 60 * 1000L;

    public record Progress(int completed, int total, Instant updatedAt) {}

    private final ConcurrentHashMap<String, Progress> progress = new ConcurrentHashMap<>();

    /** Call once, before generating the first module of a batch. */
    public void start(String avatarId, int total) {
        progress.put(avatarId, new Progress(0, total, Instant.now()));
    }

    /** Call after each module (newly generated OR idempotent-already-existed) completes. */
    public void increment(String avatarId) {
        progress.computeIfPresent(avatarId,
                (id, p) -> new Progress(p.completed() + 1, p.total(), Instant.now()));
    }

    public Progress find(String avatarId) {
        return progress.get(avatarId);
    }

    @Scheduled(fixedRate = 600_000)
    void evictExpired() {
        Instant cutoff = Instant.now().minusMillis(EXPIRY_MS);
        int before = progress.size();
        progress.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(cutoff));
        int removed = before - progress.size();
        if (removed > 0) {
            log.debug("[ModuleGenerationProgressStore] Evicted {} expired entr(y/ies)", removed);
        }
    }

    // package-private for testing
    Map<String, Progress> getProgress() { return progress; }
}
