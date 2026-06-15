package com.pally.domain.chat;

import java.time.Instant;
import java.util.Optional;

/**
 * Port for rolling per-avatar chat session summary persistence.
 */
public interface ChatSessionSummaryRepository {

    Optional<String> findSummaryByAvatarId(String avatarId);

    void upsertSummary(String avatarId, String summary, Instant updatedAt);
}
