package com.pally.domain.chat.port;

/**
 * Output port for managing the AI prompt-cache keepalive lifecycle.
 *
 * <p>The domain orchestration service signals session open/close through
 * this port so that the infrastructure adapter ({@code CacheKeepAliveService})
 * can fire periodic pings without the domain knowing about the AI client.
 */
public interface ChatSessionCachePort {

    /** Start the keepalive ticker for the given avatar's chat session. */
    void startKeepalive(String avatarId);

    /** Stop the keepalive ticker when the user leaves the chat screen. */
    void stopKeepalive(String avatarId);

    /**
     * Signal a real chat turn so an ACTIVE keepalive resets its idle timer. Lets an
     * abandoned session (one that never cleanly stopped) self-terminate after the idle
     * window instead of pinging until the process restarts. A no-op when no keepalive is
     * active — a turn never CREATES a ping loop here (chat-open does that via startKeepalive).
     */
    void recordActivity(String avatarId);
}
