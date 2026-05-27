package com.pally.domain.chat;

import java.util.List;

/**
 * Port for chat message persistence.
 */
public interface ChatRepository {

    ChatMessage save(ChatMessage message);

    /**
     * Returns chat history for an avatar, newest-first, limited to {@code limit} messages.
     */
    List<ChatMessage> findByAvatarId(String avatarId, int limit);

    /**
     * Persists cache metrics recorded from the Anthropic API usage field.
     * Called asynchronously after the SSE stream completes.
     */
    void updateCacheMetrics(String messageId, boolean cacheHit,
                            int cacheReadTokens, int cacheWriteTokens,
                            int totalInputTokens, int totalOutputTokens);

    void updateModelUsed(String messageId, String modelUsed);
}
