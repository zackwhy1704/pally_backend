package com.pally.infrastructure.persistence.chat;

import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
public class ChatRepositoryAdapter implements ChatRepository {

    private final ChatMessageJpaRepository jpaRepository;

    @Override
    @Transactional
    public ChatMessage save(ChatMessage message) {
        return jpaRepository.save(ChatMessageJpaEntity.fromDomain(message)).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> findByAvatarId(String avatarId, int limit) {
        return jpaRepository.findByAvatarIdOrderByCreatedAtDesc(
                        avatarId, PageRequest.of(0, limit))
                .stream()
                .map(ChatMessageJpaEntity::toDomain)
                .toList();
    }
}
