package com.pally.domain.chat;

import java.time.LocalDate;
import java.util.Optional;

public interface ChatSessionRepository {
    ChatSession save(ChatSession session);
    Optional<ChatSession> findByAvatarIdAndDate(String avatarId, LocalDate date);
}
