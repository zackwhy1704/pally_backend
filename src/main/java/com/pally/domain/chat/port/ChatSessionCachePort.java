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
}
