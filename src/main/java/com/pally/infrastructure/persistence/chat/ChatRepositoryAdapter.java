package com.pally.infrastructure.persistence.chat;

import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
        // Fetch the N most-recent rows with DESC so we get the right WINDOW,
        // then reverse to chronological (oldest→newest) before returning.
        // Without this reverse, Claude receives the transcript inverted and the
        // user/assistant roles are out of sequence → model fixates on the last
        // STORED topic rather than the current turn.
        var rows = jpaRepository.findByAvatarIdOrderByCreatedAtDescRoleAsc(
                avatarId, PageRequest.of(0, limit));
        var list = new java.util.ArrayList<>(
                rows.stream().map(ChatMessageJpaEntity::toDomain).toList());
        java.util.Collections.reverse(list);   // chronological: oldest → newest
        return java.util.Collections.unmodifiableList(list);
    }

    @Override
    @Transactional
    public void updateCacheMetrics(String messageId, boolean cacheHit,
                                   int cacheReadTokens, int cacheWriteTokens,
                                   int totalInputTokens, int totalOutputTokens) {
        jpaRepository.updateCacheMetrics(messageId, cacheHit,
                cacheReadTokens, cacheWriteTokens,
                totalInputTokens, totalOutputTokens);
    }

    @Override
    @Transactional
    public void updateModelUsed(String messageId, String modelUsed) {
        jpaRepository.updateModelUsed(messageId, modelUsed);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsById(String messageId) {
        return jpaRepository.existsById(messageId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> findByAvatarIdSince(String avatarId, Instant since) {
        return jpaRepository
                .findByAvatarIdAndCreatedAtAfterOrderByCreatedAtAscRoleDesc(avatarId, since)
                .stream()
                .map(ChatMessageJpaEntity::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void updateFeedbackType(String messageId, String feedbackType) {
        jpaRepository.updateFeedbackType(messageId, feedbackType);
    }

    @Override
    @Transactional
    public void markSavedToBrain(String messageId) {
        jpaRepository.markSavedToBrain(messageId);
    }
}
