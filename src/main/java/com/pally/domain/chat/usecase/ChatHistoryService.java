package com.pally.domain.chat.usecase;

import com.pally.api.chat.dto.SyncMessageDto;
import com.pally.domain.chat.ChatMessage;
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
        List<ChatMessageJpaEntity> rows = repo.findByAvatarIdOrderByCreatedAtDescRoleAsc(
                avatarId, PageRequest.of(0, limit));

        // Return in chronological order (oldest first) for the client
        List<SyncMessageDto> messages = rows.reversed().stream()
                .map(e -> new SyncMessageDto(
                        e.getId(),
                        parseRole(e.getRole()),
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

    private static ChatMessage.Role parseRole(String s) {
        if (s == null) return ChatMessage.Role.USER;
        return switch (s.toUpperCase()) {
            case "ASSISTANT", "TUTOR" -> ChatMessage.Role.ASSISTANT;
            default -> ChatMessage.Role.USER;
        };
    }
}
