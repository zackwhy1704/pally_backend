package com.pally.domain.chat.usecase;

import com.pally.domain.chat.dto.SyncMessageDto;
import com.pally.domain.chat.ChatMessage;
import com.pally.domain.chat.ChatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryService {

    private final ChatRepository chatRepo;

    @Transactional(readOnly = true)
    public List<SyncMessageDto> getHistory(String avatarId, int limit) {
        // findByAvatarId returns chronological order (oldest → newest) from the adapter
        List<ChatMessage> messages = chatRepo.findByAvatarId(avatarId, limit);

        List<SyncMessageDto> dtos = messages.stream()
                .map(msg -> new SyncMessageDto(
                        msg.getId(),
                        msg.getRole(),
                        msg.getContent(),
                        msg.getMessageType(),
                        msg.getSourceWikiSlug(),
                        msg.getFeedbackType(),
                        msg.isSavedToBrain(),
                        msg.isPhotoMessage(),
                        msg.getCreatedAt()))
                .toList();

        log.info("[ChatHistory] avatar={} returned={}", avatarId, dtos.size());
        return dtos;
    }
}
