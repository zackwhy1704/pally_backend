package com.pally.domain.chat;

import java.time.Instant;
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

    boolean existsById(String messageId);

    /// Ownership check: true only when the message belongs to the given user.
    /// Used to block cross-user feedback (IDOR) on /chat/{messageId}/feedback.
    boolean existsByIdAndUserId(String messageId, String userId);

    /**
     * Returns messages for an avatar created after {@code since}, oldest-first.
     */
    List<ChatMessage> findByAvatarIdSince(String avatarId, Instant since);

    void updateFeedbackType(String messageId, String feedbackType);

    void markSavedToBrain(String messageId);
}
