package com.pally.infrastructure.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Documents and handles cache invalidation triggers for each cache block.
 *
 * <p>Cache invalidation is automatic: changing block content produces a different
 * cache key. This service handles the cases where an explicit action is needed
 * (specifically, restarting the keepalive after a wiki update).
 */
@Service
@Slf4j
public class CacheInvalidationService {

    /**
     * Block 1 (Hard Rules) — only invalidated by app deployments.
     * No runtime action needed.
     */

    /**
     * Block 2 (Avatar Config) — called when avatar name, subject, grade,
     * curriculum, or pedagogy mode changes. The changed config produces
     * different text → different cache key. Old entry expires after 1h.
     */
    public void onAvatarConfigChanged(String avatarId, String field) {
        log.info("[CacheInvalidation] Block2 will be rebuilt: avatar={} field={} — old cache expires within 1h",
                avatarId, field);
    }

    /**
     * Block 3 (Wiki Pages) — called when wiki compilation succeeds after upload.
     * Restarts keepalive to force a fresh Block 3 cache write on the next request.
     */
    public void onWikiContentChanged(String avatarId, CacheKeepAliveService keepalive) {
        // REFRESH an existing session's keepalive so its next ping re-warms the new wiki —
        // but only when a chat session is actually live. Previously this was an unconditional
        // stop+start, so every compile (incl. on a chat-less avatar a teacher never opens)
        // spun up a 4-min ping loop that nothing stopped until redeploy — a cost line that
        // scaled with classes onboarded, not usage. No active keepalive ⇒ nothing to warm.
        if (!keepalive.isActive(avatarId)) {
            log.debug("[CacheInvalidation] Block3 invalidated: avatar={} — no active chat session, "
                    + "NOT starting keepalive (would ping a cache no one is reading)", avatarId);
            return;
        }
        log.info("[CacheInvalidation] Block3 invalidated: avatar={} — wiki updated, refreshing live keepalive",
                avatarId);
        keepalive.stopKeepalive(avatarId);
        keepalive.startKeepalive(avatarId);
    }
}
