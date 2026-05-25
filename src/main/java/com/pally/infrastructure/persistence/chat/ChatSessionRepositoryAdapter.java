package com.pally.infrastructure.persistence.chat;

import com.pally.domain.chat.ChatSession;
import com.pally.domain.chat.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ChatSessionRepositoryAdapter implements ChatSessionRepository {

    private final ChatSessionJpaRepository jpaRepository;

    @Override
    public ChatSession save(ChatSession session) {
        return jpaRepository.save(ChatSessionJpaEntity.fromDomain(session)).toDomain();
    }

    @Override
    public Optional<ChatSession> findByAvatarIdAndDate(String avatarId, LocalDate date) {
        return jpaRepository.findByAvatarIdAndSessionDate(avatarId, date)
                .map(ChatSessionJpaEntity::toDomain);
    }
}
