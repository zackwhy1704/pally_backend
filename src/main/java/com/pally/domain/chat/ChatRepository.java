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
}
