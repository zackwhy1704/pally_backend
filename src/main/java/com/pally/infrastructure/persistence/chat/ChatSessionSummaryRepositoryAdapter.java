package com.pally.infrastructure.persistence.chat;

import com.pally.domain.chat.ChatSessionSummaryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ChatSessionSummaryRepositoryAdapter implements ChatSessionSummaryRepository {

    private final ChatSessionSummaryJpaRepository jpaRepository;

    @Override
    @Transactional(readOnly = true)
    public Optional<String> findSummaryByAvatarId(String avatarId) {
        return jpaRepository.findByAvatarId(avatarId)
                .map(ChatSessionSummaryJpaEntity::getSummary);
    }

    @Override
    @Transactional
    public void upsertSummary(String avatarId, String summary, Instant updatedAt) {
        ChatSessionSummaryJpaEntity entity = jpaRepository
                .findByAvatarId(avatarId)
                .orElseGet(ChatSessionSummaryJpaEntity::new);
        entity.setAvatarId(avatarId);
        entity.setSummary(summary);
        entity.setUpdatedAt(updatedAt);
        jpaRepository.save(entity);
    }
}
