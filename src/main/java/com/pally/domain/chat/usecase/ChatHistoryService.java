package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.SyncMessageDto;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaEntity;
import com.pally.infrastructure.persistence.chat.ChatMessageJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryService {

    private final ChatMessageJpaRepository repo;

    @Transactional(readOnly = true)
    public List<SyncMessageDto> getHistory(String avatarId, int limit) {
        List<ChatMessageJpaEntity> rows = repo.findByAvatarIdOrderByCreatedAtDesc(
                avatarId, PageRequest.of(0, limit));

        // Return in chronological order (oldest first) for the client
        List<SyncMessageDto> messages = rows.reversed().stream()
                .map(e -> new SyncMessageDto(
                        e.getId(),
                        e.getRole(),
                        e.getContent(),
                        e.getMessageType(),
                        e.getSourceWikiSlug(),
                        e.getFeedbackType(),
                        e.isSavedToBrain(),
                        e.isPhotoMessage(),
                        e.getCreatedAt()))
                .toList();

        log.info("[ChatHistory] avatar={} returned={}", avatarId, messages.size());
        return messages;
    }
}
